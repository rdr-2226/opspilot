package com.divakar.opspilot.incident;

/**
 * Lifecycle of an incident.
 *
 * OPEN -> PENDING_APPROVAL (after AI triage) -> APPROVED or REJECTED (human decision) -> RESOLVED
 * A REJECTED incident can be triaged again.
 */
public enum IncidentStatus {
    OPEN,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    RESOLVED
}
