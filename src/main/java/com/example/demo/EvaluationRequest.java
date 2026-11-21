package com.example.demo;

import jakarta.validation.constraints.NotBlank;

public record EvaluationRequest(
        @NotBlank String tenantId,
        @NotBlank String resource
) {
}
