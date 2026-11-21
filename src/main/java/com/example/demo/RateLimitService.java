package com.example.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RateLimitService {
    private static final String RULE_PREFIX = "rate-limit:rule:";
    private static final String RULE_INDEX = "rate-limit:rules";
    private static final String STATE_PREFIX = "rate-limit:state:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<List> fixedWindowScript;
    private final DefaultRedisScript<List> tokenBucketScript;

    public RateLimitService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.fixedWindowScript = new DefaultRedisScript<>(FIXED_WINDOW_LUA, List.class);
        this.tokenBucketScript = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, List.class);
    }

    public RateLimitRule saveRule(RateLimitRule rule) {
        validateRule(rule);
        String ruleKey = ruleKey(rule.tenantId(), rule.resource());
        try {
            redis.opsForValue().set(ruleKey, objectMapper.writeValueAsString(rule));
            redis.opsForSet().add(RULE_INDEX, ruleKey);
            return rule;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize rate limit rule", exception);
        }
    }

    public boolean deleteRule(String tenantId, String resource) {
        String ruleKey = ruleKey(tenantId, resource);
        Boolean deleted = redis.delete(ruleKey);
        redis.opsForSet().remove(RULE_INDEX, ruleKey);
        redis.delete(STATE_PREFIX + tenantId + ":" + resource);
        return Boolean.TRUE.equals(deleted);
    }

    public List<RateLimitRule> listRules() {
        Set<String> keys = redis.opsForSet().members(RULE_INDEX);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(redis.opsForValue()::get)
                .filter(Objects::nonNull)
                .map(this::readRule)
                .sorted(java.util.Comparator.comparing(RateLimitRule::tenantId)
                        .thenComparing(RateLimitRule::resource))
                .collect(Collectors.toList());
    }

    public RateLimitRule getRule(String tenantId, String resource) {
        String json = redis.opsForValue().get(ruleKey(tenantId, resource));
        if (json == null) {
            throw new RuleNotFoundException(tenantId, resource);
        }
        return readRule(json);
    }

    public EvaluationResponse evaluate(String tenantId, String resource) {
        RateLimitRule rule = getRuleOrDefault(tenantId, resource);
        long now = System.currentTimeMillis();
        String stateKey = stateKey(tenantId, resource);

        List<?> result = rule.algorithm() == RateLimitAlgorithm.FIXED_WINDOW
                ? redis.execute(fixedWindowScript, List.of(stateKey),
                String.valueOf(now),
                String.valueOf(rule.windowSeconds() * 1000),
                String.valueOf(rule.sustainedLimit()))
                : redis.execute(tokenBucketScript, List.of(stateKey),
                String.valueOf(now),
                String.valueOf(rule.burstCapacity()),
                String.valueOf(rule.sustainedLimit()),
                String.valueOf(rule.windowSeconds() * 1000));

        if (result == null || result.size() != 3) {
            throw new IllegalStateException("Redis quota script returned an invalid result");
        }
        return new EvaluationResponse(
                result.get(0).toString().equals("1"),
                Long.parseLong(result.get(1).toString()),
                Long.parseLong(result.get(2).toString())
        );
    }

    public EvaluationResponse getQuota(String tenantId, String resource) {
        RateLimitRule rule = getRuleOrDefault(tenantId, resource);
        String stateKey = stateKey(tenantId, resource);
        long now = System.currentTimeMillis();
        return evaluateReadOnly(stateKey, rule, now);
    }

    private EvaluationResponse evaluateReadOnly(String stateKey, RateLimitRule rule, long now) {
        if (rule.algorithm() == RateLimitAlgorithm.FIXED_WINDOW) {
            Object countValue = redis.opsForHash().get(stateKey, "count");
            Object resetValue = redis.opsForHash().get(stateKey, "reset");
            if (countValue == null || resetValue == null) {
                return new EvaluationResponse(true, rule.sustainedLimit(),
                        now + rule.windowSeconds() * 1000);
            }
            return QuotaSnapshotCalculator.fixedWindow(
                    Long.parseLong(countValue.toString()),
                    Long.parseLong(resetValue.toString()), rule, now);
        }

        Object tokensValue = redis.opsForHash().get(stateKey, "tokens");
        Object updatedValue = redis.opsForHash().get(stateKey, "updated");
        if (tokensValue == null || updatedValue == null) {
            return new EvaluationResponse(true, rule.burstCapacity(), now);
        }
        return QuotaSnapshotCalculator.tokenBucket(
                Double.parseDouble(tokensValue.toString()),
                Long.parseLong(updatedValue.toString()), rule, now);
    }

    private RateLimitRule getRuleOrDefault(String tenantId, String resource) {
        String json = redis.opsForValue().get(ruleKey(tenantId, resource));
        if (json != null) {
            return readRule(json);
        }
        return new RateLimitRule(
                tenantId,
                resource,
                RateLimitAlgorithm.TOKEN_BUCKET,
                10,
                20,
                60
        );
    }

    private RateLimitRule readRule(String json) {
        try {
            return objectMapper.readValue(json, RateLimitRule.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize rate limit rule", exception);
        }
    }

    private void validateRule(RateLimitRule rule) {
        if (rule.algorithm() == RateLimitAlgorithm.TOKEN_BUCKET
                && rule.burstCapacity() < 1) {
            throw new IllegalArgumentException("burstCapacity must be at least 1");
        }
    }

    private String ruleKey(String tenantId, String resource) {
        return RULE_PREFIX + tenantId + ":" + resource;
    }

    private String stateKey(String tenantId, String resource) {
        return STATE_PREFIX + tenantId + ":" + resource;
    }

    private static final String FIXED_WINDOW_LUA = """
            local count = redis.call('HGET', KEYS[1], 'count')
            local reset = redis.call('HGET', KEYS[1], 'reset')
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])

            if not reset or tonumber(reset) <= now then
              redis.call('HSET', KEYS[1], 'count', 0, 'reset', now + window)
              count = 0
              reset = now + window
            end

            count = tonumber(count)
            if count >= limit then
              return {0, 0, tonumber(reset)}
            end

            count = count + 1
            redis.call('HSET', KEYS[1], 'count', count)
            redis.call('PEXPIRE', KEYS[1], math.max(1, tonumber(reset) - now))
            return {1, limit - count, tonumber(reset)}
            """;

    private static final String TOKEN_BUCKET_LUA = """
            local capacity = tonumber(ARGV[2])
            local sustained = tonumber(ARGV[3])
            local window = tonumber(ARGV[4])
            local now = tonumber(ARGV[1])
            local refillRate = sustained / window
            local tokens = redis.call('HGET', KEYS[1], 'tokens')
            local updated = redis.call('HGET', KEYS[1], 'updated')

            if not tokens then
              tokens = capacity
              updated = now
            else
              tokens = tonumber(tokens)
              updated = tonumber(updated)
              tokens = math.min(capacity, tokens + math.max(0, now - updated) * refillRate)
            end

            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end

            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updated', now)
            redis.call('PEXPIRE', KEYS[1], 86400000)
            local reset = now
            if tokens < 1 then
              reset = now + math.ceil((1 - tokens) / refillRate)
            end
            return {allowed, math.floor(tokens), reset}
            """;
}
