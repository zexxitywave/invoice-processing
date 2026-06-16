package com.invoice.processing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * UploadUrlHandler – generates a pre-signed S3 PUT URL so the browser can
 * upload an invoice PDF directly to S3 without credentials.
 *
 * API Gateway route:
 *   POST /invoices/upload-url
 *
 * Request body:
 *   { "fileName": "invoice_amazon_jan.pdf" }
 *
 * Response:
 *   {
 *     "uploadUrl"  : "https://s3.ap-south-1.amazonaws.com/...?X-Amz-Signature=...",
 *     "objectKey"  : "invoices/abc123-invoice_amazon_jan.pdf",
 *     "expiresInSeconds": 300
 *   }
 *
 * The browser then does:
 *   PUT <uploadUrl>  with the raw PDF bytes and Content-Type: application/pdf
 *
 * S3 → EventBridge → Step Functions fires automatically after the PUT.
 */
public class UploadUrlHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    // ── Your invoice S3 bucket name ────────────────────────────────────────────
    // Set the environment variable INVOICE_BUCKET in the Lambda console,
    // or change the fallback string to your actual bucket name.
    private static final String BUCKET_NAME = System.getenv("INVOICE_BUCKET") != null
            ? System.getenv("INVOICE_BUCKET")
            : "invoice-processing-buckets";   // ← change to your bucket name

    private static final int    URL_EXPIRY_SECONDS = 300;   // 5 minutes

    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.AP_SOUTH_1)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            context.getLogger().log("UploadUrl EVENT: "
                    + objectMapper.writeValueAsString(event));

            // ── Handle CORS preflight ──────────────────────────────────────────
            String httpMethod = (String) event.get("httpMethod");
            if ("OPTIONS".equalsIgnoreCase(httpMethod)) {
                return proxyResponse(200, "{}");
            }

            // ── Parse fileName from request body ───────────────────────────────
            String fileName = "invoice.pdf";
            String bodyStr  = (String) event.get("body");

            if (bodyStr != null && !bodyStr.isBlank()) {
                var body = objectMapper.readTree(bodyStr);
                if (body.has("fileName") && !body.get("fileName").isNull()) {
                    fileName = body.get("fileName").asText().trim();
                }
            }

            // Sanitise: keep only safe characters, force .pdf extension
            fileName = fileName
                    .replaceAll("[^a-zA-Z0-9._\\-]", "_")
                    .replaceAll("\\.pdf$", "") + ".pdf";

            // Unique key so parallel uploads never collide
            String objectKey = "invoices/" + UUID.randomUUID() + "-" + fileName;

            context.getLogger().log("Generating presigned URL for key: " + objectKey);

            // ── Generate presigned PUT URL ─────────────────────────────────────
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(objectKey)
                    .build();

            PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(
                    PutObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofSeconds(URL_EXPIRY_SECONDS))
                            .putObjectRequest(putObjectRequest)
                            .build());

            String uploadUrl = presignedRequest.url().toString();
            context.getLogger().log("Presigned URL generated (expires in "
                    + URL_EXPIRY_SECONDS + "s)");

            // ── Return to UI ───────────────────────────────────────────────────
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("uploadUrl",        uploadUrl);
            responseBody.put("objectKey",        objectKey);
            responseBody.put("bucket",           BUCKET_NAME);
            responseBody.put("expiresInSeconds", URL_EXPIRY_SECONDS);

            return proxyResponse(200, objectMapper.writeValueAsString(responseBody));

        } catch (Exception e) {
            context.getLogger().log("UploadUrl ERROR: " + e.getMessage());
            try {
                return proxyResponse(500,
                        objectMapper.writeValueAsString(
                                Map.of("error", "Failed to generate upload URL: "
                                        + e.getMessage())));
            } catch (Exception ex) {
                return proxyResponse(500, "{\"error\":\"Internal server error\"}");
            }
        }
    }

    // ── API Gateway proxy response ─────────────────────────────────────────────
    private Map<String, Object> proxyResponse(int statusCode, String jsonBody) {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", statusCode);
        response.put("headers", Map.of(
                "Content-Type",                 "application/json",
                "Access-Control-Allow-Origin",  "*",
                "Access-Control-Allow-Methods", "GET,POST,OPTIONS",
                "Access-Control-Allow-Headers", "Content-Type,Authorization"
        ));
        response.put("body", jsonBody);
        return response;
    }
}
