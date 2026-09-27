package com.divakar.opspilot.incident.dto;

import com.divakar.opspilot.incident.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateIncidentRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank
        @Pattern(regexp = "^[a-z0-9-]{2,50}$",
                message = "must be lowercase letters, digits or hyphens, e.g. payment-service")
        String serviceName,
        @Size(max = 4000) String description,
        @NotNull Severity severity) {
}
