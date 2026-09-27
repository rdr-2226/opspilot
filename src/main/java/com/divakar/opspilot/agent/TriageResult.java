package com.divakar.opspilot.agent;

import java.util.List;

/** Structured output of the Fix Planner agent. Field names match the agent's output schema. */
public record TriageResult(
        String rootCause,
        List<String> fixSteps,
        double confidence,
        String runbookUsed) {
}
