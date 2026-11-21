package com.example.demo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class RuleNotFoundException extends RuntimeException {
    public RuleNotFoundException(String tenantId, String resource) {
        super("Rate limit rule not found for tenant=" + tenantId + ", resource=" + resource);
    }
}
