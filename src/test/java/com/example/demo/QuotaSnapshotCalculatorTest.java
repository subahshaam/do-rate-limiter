package com.example.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaSnapshotCalculatorTest {
    @Test
    void fixedWindowDeniesAtLimitUntilReset() {
        RateLimitRule rule = new RateLimitRule(
                "tenant", "resource", RateLimitAlgorithm.FIXED_WINDOW, 3, 5, 10);

        EvaluationResponse response = QuotaSnapshotCalculator.fixedWindow(3, 20_000, rule, 15_000);

        assertFalse(response.allowed());
        assertEquals(0, response.remaining());
        assertEquals(20_000, response.resetAt());
    }

    @Test
    void fixedWindowResetsAtTheBoundary() {
        RateLimitRule rule = new RateLimitRule(
                "tenant", "resource", RateLimitAlgorithm.FIXED_WINDOW, 3, 5, 10);

        EvaluationResponse response = QuotaSnapshotCalculator.fixedWindow(3, 15_000, rule, 15_000);

        assertTrue(response.allowed());
        assertEquals(3, response.remaining());
        assertEquals(25_000, response.resetAt());
    }

    @Test
    void tokenBucketRefillsUpToBurstCapacity() {
        RateLimitRule rule = new RateLimitRule(
                "tenant", "resource", RateLimitAlgorithm.TOKEN_BUCKET, 10, 5, 10);

        EvaluationResponse response = QuotaSnapshotCalculator.tokenBucket(1, 1_000, rule, 10_000);

        assertTrue(response.allowed());
        assertEquals(5, response.remaining());
        assertEquals(10_000, response.resetAt());
    }

    @Test
    void fractionalTokenRefillReportsTimeUntilOneToken() {
        RateLimitRule rule = new RateLimitRule(
                "tenant", "resource", RateLimitAlgorithm.TOKEN_BUCKET, 1, 5, 10);

        EvaluationResponse response = QuotaSnapshotCalculator.tokenBucket(0, 1_000, rule, 6_000);

        assertFalse(response.allowed());
        assertEquals(0, response.remaining());
        assertEquals(11_000, response.resetAt());
    }

    @Test
    void clockMovingBackwardsDoesNotRemoveTokens() {
        RateLimitRule rule = new RateLimitRule(
                "tenant", "resource", RateLimitAlgorithm.TOKEN_BUCKET, 1, 5, 10);

        EvaluationResponse response = QuotaSnapshotCalculator.tokenBucket(2, 2_000, rule, 1_000);

        assertTrue(response.allowed());
        assertEquals(2, response.remaining());
    }
}
