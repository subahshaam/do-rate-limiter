package com.example.demo;

final class QuotaSnapshotCalculator {
    private QuotaSnapshotCalculator() {
    }

    static EvaluationResponse fixedWindow(long count, long resetAt, RateLimitRule rule, long now) {
        if (now >= resetAt) {
            return new EvaluationResponse(true, rule.sustainedLimit(),
                    now + rule.windowSeconds() * 1000);
        }
        return new EvaluationResponse(count < rule.sustainedLimit(),
                Math.max(0, rule.sustainedLimit() - count), resetAt);
    }

    static EvaluationResponse tokenBucket(double tokens, long updatedAt,
                                          RateLimitRule rule, long now) {
        double refillRate = (double) rule.sustainedLimit() / (rule.windowSeconds() * 1000);
        long elapsed = Math.max(0, now - updatedAt);
        tokens = Math.min(rule.burstCapacity(), tokens + elapsed * refillRate);
        long resetAt = now;
        if (tokens < 1) {
            resetAt = now + (long) Math.ceil((1 - tokens) / refillRate);
        }
        return new EvaluationResponse(tokens >= 1, (long) Math.floor(tokens), resetAt);
    }
}
