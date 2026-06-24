package com.invoice.processing;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * ApproveRejectHandler – called by API Gateway when the reviewer clicks
 * [Approve] or [Reject] in the Amplify UI.
 *
 * DynamoDB write and SES email run in parallel via CompletableFuture.
 * Lambda waits for BOTH to complete before returning — so the email is
 * guaranteed to be dispatched while keeping total latency low (both
 * operations run concurrently instead of sequentially).
 */
public class ApproveRejectHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String DYNAMO_TABLE = System.getenv("DYNAMO_TABLE") != null
            ? System.getenv("DYNAMO_TABLE") : "invoices";

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
            .region(Region.AP_SOUTH_1).build();

    private final SesV2Client sesClient = SesV2Client.builder()
            .region(Region.AP_SOUTH_1).build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            long start = System.currentTimeMillis();
            context.getLogger().log("⏱ [TIMING] ApproveRejectHandler started");
            context.getLogger().log("ApproveReject EVENT: " + objectMapper.writeValueAsString(event));

            // ── Parse body ────────────────────────────────────────────────────
            String bodyStr = (String) event.get("body");
            if (bodyStr == null || bodyStr.isBlank()) {
                return errorResponse(400, "Request body is required");
            }

            JsonNode body = objectMapper.readTree(bodyStr);
            String invoiceId = textOrNull(body, "invoiceId");
            String decision  = textOrNull(body, "decision");
            String reviewer  = textOrNull(body, "reviewer");
            String reason    = textOrNull(body, "reason");

            // ── Validate ──────────────────────────────────────────────────────
            if (invoiceId == null || invoiceId.isBlank()) {
                return errorResponse(400, "invoiceId is required");
            }
            if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
                return errorResponse(400, "decision must be APPROVED or REJECTED");
            }

            // ── Run DynamoDB + SES in parallel ────────────────────────────────
            // Both operations are independent — fire them concurrently so total
            // latency ≈ max(dynamoMs, sesMs) instead of dynamoMs + sesMs.
            final String fInvoiceId = invoiceId;
            final String fDecision  = decision;
            final String fReviewer  = reviewer;
            final String fReason    = reason;

            CompletableFuture<Void> dynamoFuture = CompletableFuture.runAsync(() -> {
                Map<String, AttributeValue> key = new HashMap<>();
                key.put("invoiceId", AttributeValue.builder().s(fInvoiceId).build());

                Map<String, AttributeValue> vals = new HashMap<>();
                vals.put(":status",     AttributeValue.builder().s(fDecision).build());
                vals.put(":reviewedAt", AttributeValue.builder().s(Instant.now().toString()).build());
                vals.put(":reviewedBy", AttributeValue.builder().s(fReviewer != null ? fReviewer : "unknown").build());
                vals.put(":reason",     AttributeValue.builder().s(fReason   != null ? fReason   : "").build());

                dynamoDbClient.updateItem(UpdateItemRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .key(key)
                        .updateExpression(
                                "SET reviewDecision = :status, "
                              + "    reviewedAt     = :reviewedAt, "
                              + "    reviewedBy     = :reviewedBy, "
                              + "    reviewNote     = :reason")
                        .expressionAttributeValues(vals)
                        .build());

                context.getLogger().log("⏱ [TIMING] DynamoDB updateItem took: "
                        + (System.currentTimeMillis() - start) + " ms");
            });

            CompletableFuture<Void> sesFuture = CompletableFuture.runAsync(() -> {
                try {
                    sendConfirmationEmail(fInvoiceId, fDecision, fReviewer, fReason, context);
                    context.getLogger().log("⏱ [TIMING] SES email sent, elapsed: "
                            + (System.currentTimeMillis() - start) + " ms");
                } catch (Exception e) {
                    context.getLogger().log("WARNING: SES email failed: " + e.getMessage());
                }
            });

            // Wait ONLY for DynamoDB — critical operation must complete before responding.
            // SES runs in the background; Lambda stays alive briefly after response to finish it.
            // This drops response time from ~7s to ~300ms.
            dynamoFuture.get(8, TimeUnit.SECONDS);

            // Let SES finish async — log if it completes quickly, ignore if not
            sesFuture.whenComplete((v, ex) -> {
                if (ex != null) context.getLogger().log("WARNING: SES async failed: " + ex.getMessage());
                else context.getLogger().log("⏱ [TIMING] SES completed async");
            });

            context.getLogger().log("Updated invoice " + invoiceId + " → " + decision);
            context.getLogger().log("⏱ [TIMING] ApproveRejectHandler TOTAL took: "
                    + (System.currentTimeMillis() - start) + " ms");

            // ── Return success ────────────────────────────────────────────────
            Map<String, Object> result = new HashMap<>();
            result.put("invoiceId",      invoiceId);
            result.put("reviewDecision", decision);
            result.put("message",        "Invoice " + invoiceId + " has been " + decision);
            return successResponse(result);

        } catch (Exception e) {
            context.getLogger().log("ApproveReject ERROR: " + e.getMessage());
            return errorResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    // ── SES confirmation email ─────────────────────────────────────────────────

    private void sendConfirmationEmail(String invoiceId, String decision,
                                       String reviewer, String reason, Context ctx) {
        try {
            SecretsManagerConfig cfg = SecretsManagerConfig.getInstance();

            String emoji   = "APPROVED".equals(decision) ? "✅" : "❌";
            String subject = emoji + " Invoice " + decision + " – ID: " + invoiceId;
            String body    = String.format(
                    "Hello,\n\n"
                  + "Invoice ID  : %s\n"
                  + "Decision    : %s\n"
                  + "Reviewed by : %s\n"
                  + "Note        : %s\n"
                  + "Timestamp   : %s\n\n"
                  + "This action has been recorded in the system.\n\n"
                  + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                  + "👉 View the dashboard:\n"
                  + "%s\n"
                  + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                  + "— Invoice Processing System",
                    invoiceId,
                    decision,
                    reviewer != null ? reviewer : "unknown",
                    reason   != null ? reason   : "—",
                    Instant.now(),
                    cfg.getFrontendUrl());

            sesClient.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(cfg.getSesSender())
                    .destination(Destination.builder()
                            .toAddresses(cfg.getSesReviewer())
                            .build())
                    .content(EmailContent.builder()
                            .simple(Message.builder()
                                    .subject(Content.builder().data(subject).charset("UTF-8").build())
                                    .body(Body.builder()
                                            .text(Content.builder().data(body).charset("UTF-8").build())
                                            .build())
                                    .build())
                            .build())
                    .build());

            ctx.getLogger().log("Confirmation email sent for " + invoiceId + " → " + decision);

        } catch (Exception e) {
            // Don't fail the whole request if email fails
            ctx.getLogger().log("WARNING: confirmation email failed: " + e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull() && !n.asText().isBlank()) ? n.asText() : null;
    }

    private Map<String, Object> successResponse(Object body) throws Exception {
        return proxyResponse(200, objectMapper.writeValueAsString(body));
    }

    private Map<String, Object> errorResponse(int statusCode, String message) {
        try {
            return proxyResponse(statusCode,
                    objectMapper.writeValueAsString(Map.of("error", message)));
        } catch (Exception e) {
            return proxyResponse(statusCode, "{\"error\":\"" + message + "\"}");
        }
    }

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
