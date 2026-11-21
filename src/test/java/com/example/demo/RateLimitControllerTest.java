package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RateLimitController.class)
class RateLimitControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RateLimitService rateLimitService;

    @Test
    void evaluateReturnsQuotaDecision() throws Exception {
        when(rateLimitService.evaluate("tenant-a", "search"))
                .thenReturn(new EvaluationResponse(true, 9, 1_800_000L));

        mockMvc.perform(post("/v1/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"tenant-a","resource":"search"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remaining").value(9));
    }

    @Test
    void createsRule() throws Exception {
        RateLimitRule rule = new RateLimitRule(
                "tenant-a", "search", RateLimitAlgorithm.TOKEN_BUCKET, 10, 20, 60);
        when(rateLimitService.saveRule(any(RateLimitRule.class))).thenReturn(rule);

        mockMvc.perform(post("/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rule)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"))
                .andExpect(jsonPath("$.burstCapacity").value(20));
    }

    @Test
    void rejectsMissingTenantId() throws Exception {
        mockMvc.perform(post("/v1/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"","resource":"search"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void homeEndpointReturnsStatus() throws Exception {
        mockMvc.perform(get("/v1/"))
                .andExpect(status().isOk());
    }
}
