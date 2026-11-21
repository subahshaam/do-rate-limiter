package com.example.demo;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class RateLimitController {
    private final RateLimitService service;

    public RateLimitController(RateLimitService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    public EvaluationResponse evaluate(@Valid @RequestBody EvaluationRequest request) {
        return service.evaluate(request.tenantId(), request.resource());
    }

    @GetMapping("/quota/{tenantId}/{resource}")
    public EvaluationResponse quota(@PathVariable String tenantId,
                                    @PathVariable String resource) {
        return service.getQuota(tenantId, resource);
    }

    @GetMapping("/rules")
    public List<RateLimitRule> listRules() {
        return service.listRules();
    }

    @GetMapping("/rules/{tenantId}/{resource}")
    public RateLimitRule getRule(@PathVariable String tenantId,
                                 @PathVariable String resource) {
        return service.getRule(tenantId, resource);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RateLimitRule createRule(@Valid @RequestBody RateLimitRule rule) {
        return service.saveRule(rule);
    }

    @PutMapping("/rules/{tenantId}/{resource}")
    public RateLimitRule updateRule(@PathVariable String tenantId,
                                    @PathVariable String resource,
                                    @Valid @RequestBody RateLimitRule rule) {
        if (!tenantId.equals(rule.tenantId()) || !resource.equals(rule.resource())) {
            throw new IllegalArgumentException("Path and request rule identifiers must match");
        }
        return service.saveRule(rule);
    }

    @DeleteMapping("/rules/{tenantId}/{resource}")
    public ResponseEntity<Void> deleteRule(@PathVariable String tenantId,
                                           @PathVariable String resource) {
        return service.deleteRule(tenantId, resource)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/")
    public String home() {
        return "Distributed Rate Limiter is running";
    }
}
