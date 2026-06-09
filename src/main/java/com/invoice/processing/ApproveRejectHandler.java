package com.invoice.processing;

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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * ApproveRejectHandler – called by API Gateway when the reviewer clicks
 * [Approve] or [Reject] in the Amplify UI.
 *
 * Expected request body (API Gateway proxy integration):
 * {
 *   "invoiceId" : "# 11502",
 *   "decision"  : "APPROVED" | "REJECTED",
 *   "reviewer"  : "john@example.com",   // optional – who made the decision
 *   "reason"    : "Looks good"           // optional – reviewer note
 * }
 *
 * Actions:
 *  1. Validates input
 *  2. Updates DynamoDB:  validationStatus = decision, reviewedAt, reviewedBy, reviewNote
 *  3. Sends SES confirmation email to the reviewer
 *  4. Returns 200 / 400 / 500
 */
public class ApproveRejectHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String DYNAMO_TABLE = "invoices";

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
            .region(Region.AP_SOUTH_1).build();

    private final SesV2Client sesClient = SesV2Client.builder()
            .region(Region.EU_NORTH_1).build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            context.getLogger().log("ApproveReject EVENT: " + objectMapper.writeValueAsString(event));

            // ── Parse body (API Gateway proxy wraps body as a JSON string) ─────
            String bodyStr = (String) event.get("body");
            if (bodyStr == null || bodyStr.isBlank()) {
                return errorResponse(400, "Request body is required");
            }

            JsonNode body = objectMapper.readTree(bodyStr);

            String invoiceId = textOrNull(body, "invoiceId");
            String decision  = textOrNull(body, "decision");
            String reviewer  = textOrNull(body, "reviewer");
            String reason    = textOrNull(body, "reason");

            // ── Validate ───────────────────────────────────────────────────────
            if (invoiceId == null || invoiceId.isBlank()) {
                return errorResponse(400, "invoiceId is required");
            }
            if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
                return errorResponse(400, "decision must be APPROVED or REJECTED");
            }

            // ── Update DynamoDB ────────────────────────────────────────────────
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("invoiceId", AttributeValue.builder().s(invoiceId).build());

            Map<String, AttributeValue> expressionValues = new HashMap<>();
            expressionValues.put(":status",     AttributeValue.builder().s(decision).build());
            expressionValues.put(":reviewedAt", AttributeValue.builder().s(Instant.now().toString()).build());
            expressionValues.put(":reviewedBy", AttributeValue.builder().s(reviewer != null ? reviewer : "unknown").build());
            expressionValues.put(":reason",     AttributeValue.builder().s(reason   != null ? reason   : "").build());

            // Write to reviewDecision – NOT validationStatus.
            // validationStatus stays as the AI result (APPROVED / REVIEW_REQUIRED).
            // reviewDecision is the human outcome (APPROVED / REJECTED).
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(DYNAMO_TABLE)
                    .key(key)
                    .updateExpression(
                            "SET validationStatus = :status, "
                                    + "    reviewDecision  = :status, "
                                    + "    reviewedAt      = :reviewedAt, "
                                    + "    reviewedBy      = :reviewedBy, "
                                    + "    reviewNote      = :reason")
                    .expressionAttributeValues(expressionValues)
                    .build());

            context.getLogger().log("Updated invoice " + invoiceId + " → " + decision);

            // ── Send confirmation email ────────────────────────────────────────
            sendConfirmationEmail(invoiceId, decision, reviewer, reason, context);

            // ── Return success ─────────────────────────────────────────────────
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
                  + "— Invoice Processing System",
                    invoiceId,
                    decision,
                    reviewer != null ? reviewer : "unknown",
                    reason   != null ? reason   : "—",
                    Instant.now());

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
