package com.divakar.opspilot.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the Fix Planner agent's JSON text into a {@link TriageResult} and validates it.
 *
 * Never trust model output blindly: we strip markdown fences, check required fields,
 * and clamp the confidence into the 0..1 range.
 */
public final class TriagePlanParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private TriagePlanParser() {
    }

    public static TriageResult parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new TriageException("Fix Planner returned an empty response");
        }
        int start = rawText.indexOf('{');
        int end = rawText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new TriageException("Fix Planner response is not JSON: " + abbreviate(rawText));
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(rawText.substring(start, end + 1));
        } catch (Exception e) {
            throw new TriageException("Could not parse Fix Planner JSON: " + abbreviate(rawText), e);
        }

        String rootCause = text(node, "rootCause");
        if (rootCause == null || rootCause.isBlank()) {
            throw new TriageException("Fix Planner response has no rootCause");
        }

        List<String> steps = new ArrayList<>();
        JsonNode stepsNode = node.get("fixSteps");
        if (stepsNode != null && stepsNode.isArray()) {
            stepsNode.forEach(s -> {
                String step = s.asText().replace("\n", " ").trim();
                if (!step.isEmpty()) {
                    steps.add(step);
                }
            });
        }

        double confidence = node.has("confidence") ? node.get("confidence").asDouble(0.0) : 0.0;
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        String runbook = text(node, "runbookUsed");
        return new TriageResult(rootCause.trim(), List.copyOf(steps), confidence,
                runbook == null || runbook.isBlank() ? "none" : runbook.trim());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
