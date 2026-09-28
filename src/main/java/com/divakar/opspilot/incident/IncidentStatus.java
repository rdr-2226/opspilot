package com.divakar.opspilot.incident;

/**
 * Lifecycle of an incident.
 *
 * OPEN -> TRIAGING (AI running) -> PENDING_APPROVAL -> APPROVED or REJECTED (human decision) -> RESOLVED
 * A REJECTED incident can be triaged again. If the AI fails, TRIAGING goes back to the previous status.
 */
public enum IncidentStatus {
    OPEN,
    TRIAGING,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    RESOLVED
}
