package com.divakar.opspilot.incident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for approving or rejecting the AI's proposed fix. */
public record ReviewRequest(
        @NotBlank @Size(max = 100) String reviewer,
        @Size(max = 2000) String comment) {
}
