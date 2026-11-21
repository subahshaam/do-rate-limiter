package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("public")
@TestPropertySource(properties = "RATE_LIMIT_API_KEY=integration-test-key")
class PublicApiKeyIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiRejectsRequestsWithoutBearerKey() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/v1/rules"), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void apiRejectsIncorrectBearerKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("incorrect-key");

        ResponseEntity<String> response = restTemplate.exchange(url("/v1/rules"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void apiAcceptsCorrectBearerKeyAndKeepsHealthPublic() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("integration-test-key");
        ResponseEntity<String> api = restTemplate.exchange(url("/v1/rules"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        ResponseEntity<String> health = restTemplate.getForEntity(url("/actuator/health/liveness"), String.class);

        assertEquals(HttpStatus.OK, api.getStatusCode());
        assertEquals(HttpStatus.OK, health.getStatusCode());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
