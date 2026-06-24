package com.invoice.processing;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * DailyDigestHandler – runs every morning at 08:00 IST (02:30 UTC) via EventBridge.
 *
 * Scans ALL invoices in DynamoDB, separates yesterday's from the full backlog,
 * and emails a structured daily summary report.
 */
public class DailyDigestHandler
        implements RequestHandler<Map<String, Object>, String> {

    private static final String DYNAMO_TABLE = System.getenv("DYNAMO_TABLE") != null
            ? System.getenv("DYNAMO_TABLE") : "invoices";

    private final DynamoDbClient dynamo = DynamoDbClient.builder()
            .region(Region.AP_SOUTH_1).build();

    private final SesV2Client ses = SesV2Client.builder()
            .region(Region.AP_SOUTH_1).build();

    private final SecretsManagerConfig config = SecretsManagerConfig.getInstance();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("dd MMM yyyy")
            .withZone(ZoneId.of("Asia/Kolkata"));

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm 'IST'")
            .withZone(ZoneId.of("Asia/Kolkata"));

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("DailyDigest: starting");

        Instant now       = Instant.now();
        Instant yesterday = now.minus(24, ChronoUnit.HOURS);

        // ── Scan all invoices ──────────────────────────────────────────────────
        List<Map<String, AttributeValue>> allItems  = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;
        do {
            ScanRequest.Builder req = ScanRequest.builder().tableName(DYNAMO_TABLE);
            if (lastKey != null) req.exclusiveStartKey(lastKey);
            ScanResponse resp = dynamo.scan(req.build());
            allItems.addAll(resp.items());
            lastKey = resp.lastEvaluatedKey().isEmpty() ? null : resp.lastEvaluatedKey();
        } while (lastKey != null);

        context.getLogger().log("DailyDigest: total invoices = " + allItems.size());

        // ── Categorise ────────────────────────────────────────────────────────
        int totalAll = allItems.size();

        // Yesterday's invoices
        List<Map<String, AttributeValue>> newItems = new ArrayList<>();
        for (Map<String, AttributeValue> item : allItems) {
            long ts = resolveCreatedAt(item);
            if (ts > 0 && ts >= yesterday.getEpochSecond()) {
                newItems.add(item);
            }
        }

        // Full backlog counts
        int approved        = 0, reviewRequired = 0, rejected = 0,
            escalated       = 0, duplicate = 0, pendingDecision = 0;
        double totalValue   = 0.0;

        for (Map<String, AttributeValue> item : allItems) {
            String status   = s(item, "validationStatus");
            String decision = s(item, "reviewDecision");
            double val      = parseAmount(s(item, "total"));
            totalValue += val;

            switch (status) {
                case "APPROVED"        -> approved++;
                case "REVIEW_REQUIRED" -> {
                    reviewRequired++;
                    if (decision.isBlank() || decision.equals("—")) pendingDecision++;
                }
                case "DUPLICATE"       -> duplicate++;
            }
            if ("REJECTED".equals(decision))  rejected++;
            if ("ESCALATED".equals(decision)) escalated++;
        }

        // Yesterday's breakdown
        int newIn = newItems.size();
        int newApproved = 0, newReview = 0, newDuplicate = 0;
        double newValue = 0.0;
        for (Map<String, AttributeValue> item : newItems) {
            String status = s(item, "validationStatus");
            newValue += parseAmount(s(item, "total"));
            switch (status) {
                case "APPROVED"        -> newApproved++;
                case "REVIEW_REQUIRED" -> newReview++;
                case "DUPLICATE"       -> newDuplicate++;
            }
        }

        // High-risk items needing attention
        List<String> highRisk = new ArrayList<>();
        for (Map<String, AttributeValue> item : allItems) {
            String decision = s(item, "reviewDecision");
            if ("HIGH".equals(s(item, "risk"))
                    && "REVIEW_REQUIRED".equals(s(item, "validationStatus"))
                    && (decision.isBlank() || decision.equals("—"))) {
                highRisk.add(String.format("  • %s  |  %s  |  %s",
                        s(item, "invoiceId"), s(item, "vendorName"), s(item, "total")));
            }
        }

        // ── Build email ────────────────────────────────────────────────────────
        String dateStr = DATE_FMT.format(yesterday) + " → " + DATE_FMT.format(now);

        String highRiskSection = highRisk.isEmpty()
                ? "  ✅ No high-risk invoices pending\n"
                : String.join("\n", highRisk) + "\n";

        String body = String.format(
            "📊 DAILY INVOICE DIGEST — %s\n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +

            "📥 RECEIVED IN LAST 24 HOURS\n" +
            "  Total Received   : %d invoice(s)\n" +
            "  Total Value      : ₹ / $ %.2f\n" +
            "  Auto-Approved    : %d\n" +
            "  Needs Review     : %d\n" +
            "  Duplicates       : %d\n\n" +

            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +

            "📂 FULL BACKLOG SUMMARY\n" +
            "  Total Invoices   : %d\n" +
            "  Total Value      : ₹ / $ %.2f\n" +
            "  ✅ Approved      : %d\n" +
            "  ⏳ Review Needed : %d  (pending human decision: %d)\n" +
            "  ❌ Rejected      : %d\n" +
            "  ⚠️  Duplicates   : %d\n" +
            "  🚨 Escalated     : %d\n\n" +

            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +

            "🔴 HIGH-RISK PENDING REVIEW\n" +
            "%s\n" +

            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +

            "👉 Open Dashboard: %s\n\n" +
            "— Invoice Processing System  |  %s",

            dateStr,
            newIn, newValue, newApproved, newReview, newDuplicate,
            totalAll, totalValue, approved, reviewRequired, pendingDecision,
            rejected, duplicate, escalated,
            highRiskSection,
            config.getFrontendUrl(),
            TIME_FMT.format(now));

        String subject = String.format("📊 Daily Digest – %d new invoice(s), %d pending review | %s",
                newIn, pendingDecision, DATE_FMT.format(now));

        try {
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

            context.getLogger().log("DailyDigest email sent — " + newIn + " new, " + pendingDecision + " pending");
        } catch (Exception e) {
            context.getLogger().log("DailyDigest email failed: " + e.getMessage());
        }

        return String.format("Digest sent: %d new, %d pending, %d total", newIn, pendingDecision, totalAll);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private long resolveCreatedAt(Map<String, AttributeValue> item) {
        AttributeValue ca = item.get("createdAt");
        if (ca != null && ca.n() != null) {
            try { return Long.parseLong(ca.n()); } catch (NumberFormatException ignored) {}
        }
        AttributeValue id = item.get("invoiceId");
        if (id != null && id.s() != null) {
            try {
                long ts = Long.parseLong(id.s().trim());
                return ts > 1_000_000_000_000L ? ts / 1000 : ts;
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private double parseAmount(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("—")) return 0.0;
        try {
            return Double.parseDouble(raw.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) { return 0.0; }
    }

    private String s(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return (v != null && v.s() != null) ? v.s() : "";
    }
}
