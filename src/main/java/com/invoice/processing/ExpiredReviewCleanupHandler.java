package com.invoice.processing;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * ExpiredReviewCleanupHandler – runs daily via EventBridge.
 *
 * Scans DynamoDB for invoices that are:
 *   - validationStatus = REVIEW_REQUIRED
 *   - reviewDecision is blank / missing  (no human acted)
 *   - createdAt older than 72 hours  (token has expired)
 *
 * Actions:
 *   1. Marks each such invoice as reviewDecision = ESCALATED
 *   2. Sends a single escalation summary email with fresh approve/reject links
 */
public class ExpiredReviewCleanupHandler
        implements RequestHandler<Map<String, Object>, String> {

    private static final String DYNAMO_TABLE      = System.getenv("DYNAMO_TABLE") != null
            ? System.getenv("DYNAMO_TABLE") : "invoices";
    private static final long   EXPIRY_SECONDS    = 72 * 60 * 60L;   // 72 hours
    private static final String API_BASE_URL      = System.getenv("API_BASE_URL") != null
            ? System.getenv("API_BASE_URL")
            : "https://rw5n87lye8.execute-api.ap-south-1.amazonaws.com";

    private final DynamoDbClient dynamo = DynamoDbClient.builder()
            .region(Region.AP_SOUTH_1).build();

    private final SesV2Client ses = SesV2Client.builder()
            .region(Region.AP_SOUTH_1).build();

    private final SecretsManagerConfig config = SecretsManagerConfig.getInstance();

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm 'IST'")
            .withZone(ZoneId.of("Asia/Kolkata"));

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("ExpiredReviewCleanup: starting scan");

        long cutoff = Instant.now().getEpochSecond() - EXPIRY_SECONDS;
        List<Map<String, AttributeValue>> expired = new ArrayList<>();

        // ── Scan DynamoDB for REVIEW_REQUIRED invoices with no decision ────────
        Map<String, AttributeValue> lastKey = null;
        do {
            ScanRequest.Builder req = ScanRequest.builder()
                    .tableName(DYNAMO_TABLE)
                    .filterExpression(
                            "validationStatus = :status AND " +
                            "(attribute_not_exists(reviewDecision) OR reviewDecision = :empty)")
                    .expressionAttributeValues(Map.of(
                            ":status", AttributeValue.builder().s("REVIEW_REQUIRED").build(),
                            ":empty",  AttributeValue.builder().s("").build()
                    ));

            if (lastKey != null) req.exclusiveStartKey(lastKey);

            ScanResponse resp = dynamo.scan(req.build());

            // Filter by age: invoiceId starting with timestamp means no createdAt field —
            // use the numeric invoiceId as epoch ms fallback, else check createdAt field
            for (Map<String, AttributeValue> item : resp.items()) {
                long createdAt = resolveCreatedAt(item);
                if (createdAt > 0 && createdAt < cutoff) {
                    expired.add(item);
                }
            }

            lastKey = resp.lastEvaluatedKey().isEmpty() ? null : resp.lastEvaluatedKey();
        } while (lastKey != null);

        context.getLogger().log("ExpiredReviewCleanup: found " + expired.size() + " expired invoices");

        if (expired.isEmpty()) {
            return "No expired reviews found.";
        }

        // ── Mark each as ESCALATED ─────────────────────────────────────────────
        for (Map<String, AttributeValue> item : expired) {
            String invoiceId = item.get("invoiceId").s();
            try {
                dynamo.updateItem(UpdateItemRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .key(Map.of("invoiceId", AttributeValue.builder().s(invoiceId).build()))
                        .updateExpression(
                                "SET reviewDecision = :decision, " +
                                "    reviewedAt     = :ts, " +
                                "    reviewedBy     = :by, " +
                                "    reviewNote     = :note")
                        .expressionAttributeValues(Map.of(
                                ":decision", AttributeValue.builder().s("ESCALATED").build(),
                                ":ts",       AttributeValue.builder().s(Instant.now().toString()).build(),
                                ":by",       AttributeValue.builder().s("system-cleanup").build(),
                                ":note",     AttributeValue.builder().s("Auto-escalated: 72h review window expired").build()
                        ))
                        .build());

                context.getLogger().log("Marked ESCALATED: " + invoiceId);
            } catch (Exception e) {
                context.getLogger().log("Failed to update " + invoiceId + ": " + e.getMessage());
            }
        }

        // ── Send escalation summary email ──────────────────────────────────────
        sendEscalationEmail(expired, context);

        String result = "Escalated " + expired.size() + " invoice(s).";
        context.getLogger().log(result);
        return result;
    }

    // ── Resolve createdAt as epoch seconds ─────────────────────────────────────
    private long resolveCreatedAt(Map<String, AttributeValue> item) {
        // Try explicit createdAt field (epoch seconds as number)
        AttributeValue ca = item.get("createdAt");
        if (ca != null && ca.n() != null) {
            try { return Long.parseLong(ca.n()); } catch (NumberFormatException ignored) {}
        }
        // Fallback: invoiceId that looks like a timestamp (epoch ms)
        AttributeValue id = item.get("invoiceId");
        if (id != null && id.s() != null) {
            try {
                long ts = Long.parseLong(id.s().trim());
                // epoch ms → epoch seconds
                if (ts > 1_000_000_000_000L) return ts / 1000;
                return ts;
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    // ── Send escalation summary email ──────────────────────────────────────────
    private void sendEscalationEmail(List<Map<String, AttributeValue>> items, Context ctx) {
        try {
            StringBuilder rows = new StringBuilder();
            long newExp = Instant.now().getEpochSecond() + EXPIRY_SECONDS;

            for (Map<String, AttributeValue> item : items) {
                String id     = s(item, "invoiceId");
                String vendor = s(item, "vendorName");
                String total  = s(item, "total");

                String approveToken = buildToken(id, "APPROVED", newExp);
                String rejectToken  = buildToken(id, "REJECTED",  newExp);
                String approveLink  = API_BASE_URL + "/invoices/approve?token=" + approveToken;
                String rejectLink   = API_BASE_URL + "/invoices/reject?token="  + rejectToken;

                rows.append(String.format(
                        "  Invoice ID : %s\n  Vendor     : %s\n  Total      : %s\n" +
                        "  ✅ Approve : %s\n  ❌ Reject  : %s\n\n",
                        id, vendor, total, approveLink, rejectLink));
            }

            String subject = "🚨 " + items.size() + " Invoice(s) Escalated – Review Window Expired";
            String body = String.format(
                    "Hello,\n\n" +
                    "%d invoice(s) were not reviewed within 72 hours and have been auto-escalated.\n" +
                    "Fresh approve/reject links (valid 72h) are provided below.\n\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                    "%s" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                    "Dashboard: %s\n\n" +
                    "— Invoice Processing System  |  %s",
                    items.size(), rows, config.getFrontendUrl(),
                    FMT.format(Instant.now()));

            ses.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(config.getSesSender())
                    .destination(Destination.builder().toAddresses(config.getSesReviewer()).build())
                    .content(EmailContent.builder()
                            .simple(Message.builder()
                                    .subject(Content.builder().data(subject).charset("UTF-8").build())
                                    .body(Body.builder()
                                            .text(Content.builder().data(body).charset("UTF-8").build())
                                            .build())
                                    .build())
                            .build())
                    .build());

            ctx.getLogger().log("Escalation email sent for " + items.size() + " invoice(s)");
        } catch (Exception e) {
            ctx.getLogger().log("WARNING: escalation email failed: " + e.getMessage());
        }
    }

    private String buildToken(String invoiceId, String decision, long exp) {
        try {
            String json = String.format(
                    "{\"invoiceId\":\"%s\",\"decision\":\"%s\",\"exp\":%d}",
                    invoiceId.replace("\"", "\\\""), decision, exp);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { return ""; }
    }

    private String s(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return (v != null && v.s() != null) ? v.s() : "—";
    }
}
