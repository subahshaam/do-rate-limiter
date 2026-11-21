package com.example.demo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RateLimitRule(
        @NotBlank String tenantId,
        @NotBlank String resource,
        @NotNull RateLimitAlgorithm algorithm,
        @Min(1) int sustainedLimit,
        @Min(1) int burstCapacity,
        @Min(1) long windowSeconds
) {
}
