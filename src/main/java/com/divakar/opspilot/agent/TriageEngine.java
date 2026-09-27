package com.divakar.opspilot.agent;

import com.divakar.opspilot.incident.Incident;

/**
 * Anything that can analyse an incident and propose a fix.
 *
 * The real implementation is {@link AdkTriageEngine} (Google ADK agents calling Gemini).
 * Keeping it behind an interface lets tests replace it with a mock, so the test suite
 * runs without an API key or network access.
 */
public interface TriageEngine {

    TriageResult triage(Incident incident);
}
