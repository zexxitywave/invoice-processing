package com.invoice.processing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends transactional emails through the Brevo (Sendinblue) API v3
 * (https://api.brevo.com/v3/smtp/email).
 *
 * The API key is read from Secrets Manager via {@link SecretsManagerConfig}
 * (field "brevoApiKey"). No secret is hardcoded in source.
 */
public final class BrevoMailer {

    private static final String BREVO_ENDPOINT = "https://api.brevo.com/v3/smtp/email";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private BrevoMailer() {
    }

    /** Send a plain-text transactional email via Brevo. Throws on failure. */
    public static void send(String from, String to, String subject, String textContent) throws Exception {
        send(from, to, subject, textContent, null);
    }

    /** Send a transactional email via Brevo with both text and optional HTML bodies. */
    public static void send(String from, String to, String subject, String textContent, String htmlContent)
            throws Exception {
        SecretsManagerConfig cfg = SecretsManagerConfig.getInstance();
        String apiKey = cfg.getBrevoApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Brevo API key is not configured "
                    + "(set brevoApiKey in the invoice-processing/config secret)");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", Map.of("name", "zexxity", "email", from));
        body.put("to", List.of(Map.of("email", to)));
        body.put("subject", subject);
        body.put("textContent", textContent);
        if (htmlContent != null && !htmlContent.isBlank()) {
            body.put("htmlContent", htmlContent);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BREVO_ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Brevo send failed: HTTP " + response.statusCode()
                    + " -> " + response.body());
        }
    }
}