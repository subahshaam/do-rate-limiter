package com.example.demo;

public record EvaluationResponse(
        boolean allowed,
        long remaining,
        long resetAt
) {
}
