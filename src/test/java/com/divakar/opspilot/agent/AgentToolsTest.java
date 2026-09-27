package com.divakar.opspilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.adk.agents.BaseAgent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the agents' tools and the output parser. No LLM calls. */
class AgentToolsTest {

    @Test
    void fetchLogs_returnsRecentLinesAndCounts() {
        Map<String, Object> result = LogTools.fetchLogs("payment-service");

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat((Long) result.get("errorCount")).isGreaterThan(0);
        assertThat((String) result.get("logs")).contains("HikariPool-1");
    }

    @Test
    void fetchLogs_blocksPathTraversal() {
        assertThat(LogTools.fetchLogs("../application").get("status")).isEqualTo("error");
        assertThat(LogTools.fetchLogs("Payment Service").get("status")).isEqualTo("error");
    }

    @Test
    void fetchLogs_unknownService() {
        assertThat(LogTools.fetchLogs("unknown-service").get("status")).isEqualTo("not_found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchRunbooks_findsTheRightRunbook() {
        Map<String, Object> result = RunbookTools.searchRunbooks("Hikari connection pool timeout JDBC");

        assertThat(result.get("status")).isEqualTo("ok");
        List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("results");
        assertThat(hits.get(0).get("fileName")).isEqualTo("db-connection-pool-exhaustion.md");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchRunbooks_oomQuery() {
        Map<String, Object> result = RunbookTools.searchRunbooks("OutOfMemoryError OOMKilled heap");
        List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("results");
        assertThat(hits.get(0).get("fileName")).isEqualTo("pod-oomkilled.md");
    }

    @Test
    void searchRunbooks_noMatch() {
        assertThat(RunbookTools.searchRunbooks("zebra giraffe").get("status")).isEqualTo("no_match");
    }

    @Test
    void parser_handlesJsonWrappedInMarkdownFences() {
        String raw = """
                ```json
                {"rootCause": "Pool exhausted", "fixSteps": ["Add index", "Raise pool"],
                 "confidence": 1.7, "runbookUsed": "db-connection-pool-exhaustion.md"}
                ```
                """;
        TriageResult r = TriagePlanParser.parse(raw);

        assertThat(r.rootCause()).isEqualTo("Pool exhausted");
        assertThat(r.fixSteps()).containsExactly("Add index", "Raise pool");
        assertThat(r.confidence()).isEqualTo(1.0); // clamped into 0..1
    }

    @Test
    void parser_rejectsMissingRootCause() {
        assertThatThrownBy(() -> TriagePlanParser.parse("{\"fixSteps\": []}"))
                .isInstanceOf(TriageException.class);
        assertThatThrownBy(() -> TriagePlanParser.parse("I think it is the database"))
                .isInstanceOf(TriageException.class);
    }

    @Test
    void retry_onlyForTemporaryErrors() {
        var overloaded = new RuntimeException(new RuntimeException("503 . This model is currently experiencing high demand"));
        var rateLimited = new RuntimeException("429 RESOURCE_EXHAUSTED");
        var timeout = new RuntimeException(new java.util.concurrent.TimeoutException());
        var badKey = new RuntimeException("400 API key not valid");

        assertThat(AdkTriageEngine.isTemporary(overloaded)).isTrue();
        assertThat(AdkTriageEngine.isTemporary(rateLimited)).isTrue();
        assertThat(AdkTriageEngine.isTemporary(timeout)).isTrue();
        assertThat(AdkTriageEngine.isTemporary(badKey)).isFalse();
    }

    @Test
    void pipeline_isWiredInTheRightOrder() {
        var pipeline = AdkTriageEngine.buildPipeline("gemini-2.5-flash");
        List<String> names = pipeline.subAgents().stream().map(BaseAgent::name).toList();

        assertThat(names).containsExactly("LogAnalyst", "RunbookAgent", "FixPlanner");
    }
}
