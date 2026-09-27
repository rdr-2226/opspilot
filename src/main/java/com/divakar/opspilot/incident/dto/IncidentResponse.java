package com.divakar.opspilot.incident.dto;

import com.divakar.opspilot.incident.Incident;
import com.divakar.opspilot.incident.IncidentStatus;
import com.divakar.opspilot.incident.Severity;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** What the API returns. We never expose the JPA entity directly. */
public record IncidentResponse(
        Long id,
        String title,
        String serviceName,
        String description,
        Severity severity,
        IncidentStatus status,
        String rootCause,
        List<String> suggestedFix,
        Double confidence,
        String runbookUsed,
        String reviewedBy,
        String reviewComment,
        Instant createdAt,
        Instant updatedAt) {

    public static IncidentResponse from(Incident i) {
        List<String> steps = i.getSuggestedFix() == null
                ? List.of()
                : Arrays.stream(i.getSuggestedFix().split("\n")).filter(s -> !s.isBlank()).toList();
        return new IncidentResponse(
                i.getId(), i.getTitle(), i.getServiceName(), i.getDescription(),
                i.getSeverity(), i.getStatus(), i.getRootCause(), steps,
                i.getConfidence(), i.getRunbookUsed(), i.getReviewedBy(), i.getReviewComment(),
                i.getCreatedAt(), i.getUpdatedAt());
    }
}
