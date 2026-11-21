package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisBackedIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void ruleCrudWorksAgainstRedisOverHttp() {
        String tenant = tenant();
        RateLimitRule initial = rule(tenant, RateLimitAlgorithm.FIXED_WINDOW, 4, 4, 60);

        ResponseEntity<RateLimitRule> created = restTemplate.postForEntity(
                url("/v1/rules"), initial, RateLimitRule.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        RateLimitRule fetched = restTemplate.getForObject(
                url("/v1/rules/{tenant}/search"), RateLimitRule.class, tenant);
        assertNotNull(fetched);
        assertEquals(RateLimitAlgorithm.FIXED_WINDOW, fetched.algorithm());

        ResponseEntity<List<RateLimitRule>> listed = restTemplate.exchange(
                url("/v1/rules"), HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertTrue(listed.getBody().stream().anyMatch(rule -> rule.tenantId().equals(tenant)));

        RateLimitRule updated = rule(tenant, RateLimitAlgorithm.TOKEN_BUCKET, 2, 6, 30);
        ResponseEntity<RateLimitRule> updateResponse = restTemplate.exchange(
                url("/v1/rules/{tenant}/search"), HttpMethod.PUT,
                new HttpEntity<>(updated), RateLimitRule.class, tenant);
        assertEquals(HttpStatus.OK, updateResponse.getStatusCode());
        assertEquals(RateLimitAlgorithm.TOKEN_BUCKET, updateResponse.getBody().algorithm());

        ResponseEntity<Void> deleted = restTemplate.exchange(
                url("/v1/rules/{tenant}/search"), HttpMethod.DELETE, null, Void.class, tenant);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, restTemplate.getForEntity(
                url("/v1/rules/{tenant}/search"), String.class, tenant).getStatusCode());
    }

    @Test
    void concurrentRequestsDoNotOverspendFixedWindowQuota() throws Exception {
        String tenant = tenant();
        restTemplate.postForEntity(url("/v1/rules"),
                rule(tenant, RateLimitAlgorithm.FIXED_WINDOW, 5, 5, 60), RateLimitRule.class);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Callable<EvaluationResponse>> calls = java.util.stream.IntStream.range(0, 20)
                    .<Callable<EvaluationResponse>>mapToObj(index -> () -> restTemplate.postForObject(
                            url("/v1/evaluate"), new EvaluationRequest(tenant, "search"),
                            EvaluationResponse.class))
                    .toList();
            List<Future<EvaluationResponse>> results = executor.invokeAll(calls);
            long allowed = 0;
            for (Future<EvaluationResponse> result : results) {
                if (result.get().allowed()) {
                    allowed++;
                }
            }
            assertEquals(5, allowed);
            assertEquals(15, results.size() - allowed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void tokenBucketRefillsAfterSustainedInterval() throws InterruptedException {
        String tenant = tenant();
        restTemplate.postForEntity(url("/v1/rules"),
                rule(tenant, RateLimitAlgorithm.TOKEN_BUCKET, 1, 1, 1), RateLimitRule.class);

        EvaluationResponse first = evaluate(tenant);
        EvaluationResponse beforeRefill = evaluate(tenant);
        Thread.sleep(1_200);
        EvaluationResponse afterRefill = evaluate(tenant);

        assertTrue(first.allowed());
        assertEquals(false, beforeRefill.allowed());
        assertTrue(afterRefill.allowed());
    }

    @Test
    void healthEndpointsReportRedisReadiness() {
        ResponseEntity<JsonNode> health = restTemplate.getForEntity(url("/actuator/health"), JsonNode.class);
        ResponseEntity<JsonNode> readiness = restTemplate.getForEntity(
                url("/actuator/health/readiness"), JsonNode.class);
        ResponseEntity<JsonNode> liveness = restTemplate.getForEntity(
                url("/actuator/health/liveness"), JsonNode.class);

        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertEquals("UP", health.getBody().path("status").asText());
        assertEquals("UP", readiness.getBody().path("status").asText());
        assertEquals("UP", liveness.getBody().path("status").asText());
    }

    private EvaluationResponse evaluate(String tenant) {
        return restTemplate.postForObject(url("/v1/evaluate"),
                new EvaluationRequest(tenant, "search"), EvaluationResponse.class);
    }

    private RateLimitRule rule(String tenant, RateLimitAlgorithm algorithm,
                               int sustainedLimit, int burstCapacity, long windowSeconds) {
        return new RateLimitRule(tenant, "search", algorithm,
                sustainedLimit, burstCapacity, windowSeconds);
    }

    private String tenant() {
        return "integration-" + UUID.randomUUID();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
