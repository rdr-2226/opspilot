package com.divakar.opspilot.agent;

import com.divakar.opspilot.incident.Incident;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.util.List;
import java.util.Map;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The multi-agent triage pipeline, built with Google ADK for Java.
 *
 * <pre>
 *   incident ──► LogAnalyst ──► RunbookAgent ──► FixPlanner ──► TriageResult (JSON)
 *                  │ tool:          │ tool:            │ output schema,
 *                  │ fetchLogs      │ searchRunbooks   │ no tools
 *                  ▼                ▼                  ▼
 *            state["log_findings"] state["runbook_context"] state["triage_plan"]
 * </pre>
 *
 * A SequentialAgent runs the three agents in order. Each agent writes its answer into the shared
 * session state via outputKey, and the next agent reads it through a {placeholder} in its instruction.
 */
@Service
public class AdkTriageEngine implements TriageEngine {

    private static final Logger log = LoggerFactory.getLogger(AdkTriageEngine.class);

    static final String APP_NAME = "opspilot";
    private static final String USER_ID = "opspilot-api";
    private static final String FIX_PLANNER = "FixPlanner";
    /** Max seconds to wait between two events from the pipeline (configurable). */
    private final long timeoutSeconds;
    /** Total tries for temporary errors (1 first try + 3 retries). */
    private static final int MAX_ATTEMPTS = 4;

    private final InMemoryRunner runner;

    public AdkTriageEngine(@Value("${opspilot.agent.model}") String model,
                           @Value("${opspilot.agent.timeout-seconds:180}") long timeoutSeconds) {
        this.runner = new InMemoryRunner(buildPipeline(model), APP_NAME);
        this.timeoutSeconds = timeoutSeconds;
        log.info("ADK triage pipeline ready (model={}, timeout={}s)", model, timeoutSeconds);
    }

    static SequentialAgent buildPipeline(String model) {

        // 1) Reads the service logs and summarises what went wrong.
        LlmAgent logAnalyst = LlmAgent.builder()
                .name("LogAnalyst")
                .model(model)
                .description("Reads a service's recent logs and summarises the errors.")
                .instruction("""
                        You are an experienced site reliability engineer.
                        The user message describes a production incident, including the service name.
                        1. Call the fetchLogs tool with that exact service name.
                        2. Study the ERROR and WARN lines and the order in which they happened.
                        3. Reply with a short factual summary: the main error messages, when the problems
                           started, which dependency or component looks involved, and any numbers such as
                           pool sizes, memory usage or latency.
                        If no logs are found, say so plainly. Do not guess the fix yet.
                        """)
                .tools(FunctionTool.create(LogTools.class, "fetchLogs"))
                .outputKey("log_findings")
                .build();

        // 2) Retrieves the relevant runbook(s) - retrieval-augmented generation.
        LlmAgent runbookAgent = LlmAgent.builder()
                .name("RunbookAgent")
                .model(model)
                .description("Finds the team runbooks relevant to the log findings.")
                .instruction("""
                        You look up internal runbooks for on-call engineers.
                        Log findings from the previous step:
                        {log_findings}

                        Call the searchRunbooks tool with a short keyword query built from the key error
                        terms in the findings. Then reply with the file name of the best runbook and copy
                        its diagnosis and fix steps that apply here. If nothing matched, reply "No runbook found".
                        """)
                .tools(FunctionTool.create(RunbookTools.class, "searchRunbooks"))
                .outputKey("runbook_context")
                .build();

        // 3) Combines everything into a structured, machine-readable plan.
        LlmAgent fixPlanner = LlmAgent.builder()
                .name(FIX_PLANNER)
                .model(model)
                .description("Produces the root cause and a step-by-step fix plan.")
                .instruction("""
                        You are the incident commander. Using only the evidence below, decide the most
                        likely root cause and a safe fix plan. Prefer the runbook steps when they apply.

                        Log findings:
                        {log_findings}

                        Runbook context:
                        {runbook_context}

                        Rules:
                        - rootCause: one or two sentences, specific to this incident.
                        - fixSteps: 3 to 6 short, ordered, actionable steps. Start with the safest step.
                        - confidence: a number from 0 to 1. Use below 0.5 when the evidence is weak.
                        - runbookUsed: the runbook file name you relied on, or "none".
                        """)
                .outputSchema(triagePlanSchema())
                .outputKey("triage_plan")
                .build();

        return SequentialAgent.builder()
                .name("OpsPilotTriagePipeline")
                .description("Log analysis, then runbook retrieval, then fix planning.")
                .subAgents(logAnalyst, runbookAgent, fixPlanner)
                .build();
    }

    /** JSON schema the Fix Planner must follow, so we get structured output instead of free text. */
    static Schema triagePlanSchema() {
        return Schema.builder()
                .type("OBJECT")
                .description("Root cause analysis and fix plan for an incident.")
                .properties(Map.of(
                        "rootCause", Schema.builder().type("STRING")
                                .description("Most likely root cause.").build(),
                        "fixSteps", Schema.builder().type("ARRAY")
                                .items(Schema.builder().type("STRING").build())
                                .description("Ordered fix steps.").build(),
                        "confidence", Schema.builder().type("NUMBER")
                                .description("Confidence from 0 to 1.").build(),
                        "runbookUsed", Schema.builder().type("STRING")
                                .description("Runbook file name used, or none.").build()))
                .required(List.of("rootCause", "fixSteps", "confidence", "runbookUsed"))
                .build();
    }

    @Override
    public TriageResult triage(Incident incident) {
        String prompt = """
                Incident #%d: %s
                Service name: %s
                Severity: %s
                Description: %s
                """.formatted(
                incident.getId(), incident.getTitle(), incident.getServiceName(),
                incident.getSeverity(),
                incident.getDescription() == null ? "(none)" : incident.getDescription());

        // Retry with exponential backoff: 2s, 4s, 8s ... only for temporary errors (503, 429, timeouts).
        for (int attempt = 1; ; attempt++) {
            try {
                return runPipelineOnce(incident, prompt);
            } catch (TriageException e) {
                throw e; // bad model output: retrying the same thing won't help
            } catch (RuntimeException e) {
                if (!isTemporary(e) || attempt >= MAX_ATTEMPTS) {
                    throw new TriageException("AI triage failed after " + attempt + " attempt(s): "
                            + e.getMessage(), e);
                }
                // Rate limits (429) reset per minute, so wait longer for them: 20s, 40s, 60s.
                // Overload / timeouts: short exponential backoff: 2s, 4s, 8s.
                long waitMs = isRateLimit(e)
                        ? 20_000L * attempt
                        : 2000L * (1L << (attempt - 1));
                log.warn("[incident {}] attempt {}/{} failed with a temporary error ({}). Retrying in {} ms",
                        incident.getId(), attempt, MAX_ATTEMPTS, rootMessage(e), waitMs);
                sleep(waitMs);
            }
        }
    }

    private TriageResult runPipelineOnce(Incident incident, String prompt) {
        // A fresh session per attempt, so incidents (and retries) never share state.
        Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
        Content userMessage = Content.fromParts(Part.fromText(prompt));

        AtomicReference<String> plan = new AtomicReference<>();
        log.info("[incident {}] triage started (model call in progress...)", incident.getId());
        runner.runAsync(USER_ID, session.id(), userMessage)
                // Run the pipeline on a background I/O thread. Without this, a blocking model call
                // would run on THIS thread and block it, so the timeout below could never fire.
                .subscribeOn(Schedulers.io())
                // Fail instead of hanging forever if the model or network stops responding.
                .timeout(timeoutSeconds, TimeUnit.SECONDS)
                .blockingForEach((Event event) -> {
                    if (event.finalResponse()) {
                        log.info("[incident {}] {} finished: {}", incident.getId(), event.author(),
                                abbreviate(event.stringifyContent()));
                        if (FIX_PLANNER.equals(event.author())) {
                            plan.set(event.stringifyContent());
                        }
                    } else {
                        // Intermediate events: tool calls and tool results. Great for learning/debugging.
                        log.info("[incident {}] {} -> {}", incident.getId(), event.author(),
                                abbreviate(event.stringifyContent()));
                    }
                });

        if (plan.get() == null) {
            throw new TriageException("Pipeline finished without a response from " + FIX_PLANNER);
        }
        return TriagePlanParser.parse(plan.get());
    }

    /** Temporary = worth retrying: server overload (5xx), rate limit (429) or timeout. */
    static boolean isTemporary(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof com.google.genai.errors.ServerException
                    || t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String msg = String.valueOf(t.getMessage());
            if (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") || msg.contains("503")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    static boolean isRateLimit(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            String msg = String.valueOf(t.getMessage());
            if (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED")) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return abbreviate(t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TriageException("Interrupted while waiting to retry", ie);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replace('\n', ' ');
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }
}
