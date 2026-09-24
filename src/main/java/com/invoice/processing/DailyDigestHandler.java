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

        // Full backlog counts – buckets are mutually exclusive and sum to totalAll:
        // approved (AI or human), duplicate, rejected, escalated, awaiting decision.
        int approved = 0, needsReview = 0, rejected = 0,
            escalated = 0, duplicate = 0;

        for (Map<String, AttributeValue> item : allItems) {
            String status   = s(item, "validationStatus");
            String decision = s(item, "reviewDecision");

            switch (status) {
                case "APPROVED"        -> approved++;
                case "DUPLICATE"       -> duplicate++;
                case "REVIEW_REQUIRED" -> {
                    switch (decision) {
                        case "REJECTED"  -> rejected++;
                        case "ESCALATED" -> escalated++;
                        case "APPROVED"  -> approved++;   // human-approved after review
                        default          -> needsReview++; // still awaiting a decision
                    }
                }
            }
        }

        // Yesterday's breakdown
        int newIn = newItems.size();
        int newApproved = 0, newReview = 0, newDuplicate = 0;
        for (Map<String, AttributeValue> item : newItems) {
            String status = s(item, "validationStatus");
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
        String dateStr = DATE_FMT.format(yesterday) + " – " + DATE_FMT.format(now);

        String text = buildTextDigest(dateStr, newIn, newApproved,
                newReview, newDuplicate, totalAll, approved, needsReview,
                rejected, duplicate, escalated, highRisk, config.getFrontendUrl());

        String html = buildHtmlDigest(dateStr, newIn, newApproved,
                newReview, newDuplicate, totalAll, approved, needsReview,
                rejected, duplicate, escalated, highRisk, config.getFrontendUrl());

        String subject = String.format("Daily Invoice Digest — %d new · %d pending review · %s",
                newIn, needsReview, DATE_FMT.format(now));

        try {
            BrevoMailer.send(config.getBrevoSender(), config.getSesReviewer(), subject, text, html);

            context.getLogger().log("DailyDigest email sent — " + newIn + " new, " + needsReview + " pending");
        } catch (Exception e) {
            context.getLogger().log("DailyDigest email failed: " + e.getMessage());
        }

        return String.format("Digest sent: %d new, %d pending, %d total", newIn, needsReview, totalAll);
    }

    // ── Email rendering ────────────────────────────────────────────────────────

    private String buildTextDigest(String dateStr, int newIn,
            int newApproved, int newReview, int newDuplicate, int totalAll,
            int approved, int needsReview, int rejected, int duplicate,
            int escalated, List<String> highRisk, String frontendUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("DAILY INVOICE DIGEST - ").append(dateStr).append("\n\n");
        sb.append("RECEIVED IN LAST 24 HOURS\n");
        sb.append("  Received     : ").append(newIn).append("\n");
        sb.append("  Auto-approved: ").append(newApproved).append("\n");
        sb.append("  Needs review : ").append(newReview).append("\n");
        sb.append("  Duplicates   : ").append(newDuplicate).append("\n\n");
        sb.append("FULL BACKLOG SUMMARY\n");
        sb.append("  Invoices      : ").append(totalAll).append("\n");
        sb.append("  Approved      : ").append(approved).append("\n");
        sb.append("  Needs review  : ").append(needsReview).append("\n");
        sb.append("  Rejected      : ").append(rejected).append("\n");
        sb.append("  Duplicates    : ").append(duplicate).append("\n");
        sb.append("  Escalated     : ").append(escalated).append("\n\n");

        if (highRisk.isEmpty()) {
            sb.append("HIGH-RISK PENDING REVIEW: none - all clear.\n\n");
        } else {
            sb.append("HIGH-RISK PENDING REVIEW\n");
            for (String row : highRisk) {
                sb.append("  ").append(row).append("\n");
            }
            sb.append("\n");
        }
        sb.append("Open the dashboard: ").append(frontendUrl).append("\n");
        sb.append("Generated by the Invoice Processing System\n");
        return sb.toString();
    }

    private String buildHtmlDigest(String dateStr, int newIn,
            int newApproved, int newReview, int newDuplicate, int totalAll,
            int approved, int needsReview, int rejected, int duplicate,
            int escalated, List<String> highRisk, String frontendUrl) {
        StringBuilder h = new StringBuilder();
        h.append("<html><body style=\"margin:0;padding:0;background:#f4f6f8;")
          .append("font-family:Arial,Helvetica,sans-serif;color:#1f2d3d;\">");

        // Header
        h.append("<div style=\"background:#1e2a3a;padding:26px 36px;\">");
        h.append("<div style=\"color:#ffffff;font-size:20px;font-weight:bold;\">Invoice Processing</div>");
        h.append("<div style=\"color:#9fb3c8;font-size:13px;margin-top:6px;\">Daily Digest | ").append(esc(dateStr)).append("</div>");
        h.append("</div>");

        h.append("<div style=\"padding:28px 36px;\">");

        // 24h KPI cards
        h.append(kpiRow(newIn, newApproved, newReview, newDuplicate));

        h.append("<div style=\"margin-top:26px;\"></div>");
        h.append(sectionTitle("Since yesterday"));
        h.append(summaryRows("Received", kpi(newIn), false));
        h.append(summaryRows("Auto-approved", kpi(newApproved), false));
        h.append(summaryRows("Needs review", kpi(newReview), false));
        h.append(summaryRows("Duplicates", kpi(newDuplicate), false));

        h.append("<div style=\"height:22px;\"></div>");
        h.append(sectionTitle("Full backlog"));
        h.append(summaryRows("Total invoices", kpi(totalAll), false));
        h.append(summaryRows("Approved", kpi(approved), false));
        h.append(summaryRows("Needs review", kpi(needsReview)
                + " <span style=\"color:#7a8b9d;\">(awaiting decision)</span>", false));
        h.append(summaryRows("Rejected", kpi(rejected), false));
        h.append(summaryRows("Duplicates", kpi(duplicate), false));
        h.append(summaryRows("Escalated", kpi(escalated), false));

        h.append("<div style=\"height:22px;\"></div>");
        if (highRisk.isEmpty()) {
            h.append("<div style=\"background:#e8f5e9;border:1px solid #a5d6a7;color:#2e7d32;")
             .append("padding:12px 16px;border-radius:6px;font-size:14px;\">"
              + "No high-risk invoices awaiting review. All clear.</div>");
        } else {
            h.append(sectionTitle("High-risk pending review"));
            h.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
             .append("style=\"border-collapse:collapse;font-size:13px;\">");
            h.append("<tr style=\"background:#f8fafc;color:#5b6b7c;text-transform:uppercase;font-size:11px;\">")
             .append("<td style=\"padding:8px 12px;border:1px solid #e3e8ee;\">Invoice ID</td>")
             .append("<td style=\"padding:8px 12px;border:1px solid #e3e8ee;\">Vendor</td>")
             .append("<td style=\"padding:8px 12px;border:1px solid #e3e8ee;\">Amount</td></tr>");
            for (String row : highRisk) {
                String[] parts = row.split("\\|");
                String id = parts.length > 0 ? parts[0].trim() : "";
                String vendor = parts.length > 1 ? parts[1].trim() : "";
                String amount = parts.length > 2 ? parts[2].trim() : "";
                h.append("<tr>")
                 .append("<td style=\"padding:8px 12px;border:1px solid #e3e8ee;\">").append(esc(id)).append("</td>")
                 .append("<td style=\"padding:8px 12px;border:1px solid #e3e8ee;\">").append(esc(blankToDash(vendor))).append("</td>")
                 .append("<td style=\"padding:8px 12px;border:1px solid #e3e8ee;\">").append(esc(blankToDash(amount))).append("</td></tr>");
            }
            h.append("</table>");
        }

        h.append("<div style=\"margin-top:28px;border-top:1px solid #e3e8ee;padding-top:16px;color:#7a8b9d;font-size:12px;\">");
        h.append("Open the review dashboard: ")
         .append("<a href=\"").append(esc(frontendUrl)).append("\" style=\"color:#2a6fb0;\">").append(esc(frontendUrl)).append("</a><br>");
        h.append("Generated by the Invoice Processing System</div>");

        h.append("</div></body></html>");
        return h.toString();
    }

    private String kpiRow(int received, int approved, int review, int dup) {
        StringBuilder t = new StringBuilder();
        t.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;\"><tr>");
        t.append(kpiCell("#eef3f8", "Received", kpi(received)));
        t.append(kpiCell("#e8f5e9", "Approved", kpi(approved)));
        t.append(kpiCell("#fff8e1", "Needs review", kpi(review)));
        t.append(kpiCell("#fce8e6", "Duplicates", kpi(dup)));
        t.append("</tr></table>");
        return t.toString();
    }

    private String kpiCell(String bg, String label, String value) {
        return "<td style=\"background:" + bg + ";border:1px solid #e3e8ee;border-radius:6px;"
             + "padding:14px 10px;text-align:center;width:25%;\">"
             + "<div style=\"font-size:11px;color:#5b6b7c;text-transform:uppercase;letter-spacing:.5px;\">"
             + esc(label) + "</div>"
             + "<div style=\"font-size:22px;font-weight:bold;color:#1f2d3d;margin-top:6px;\">"
             + esc(value) + "</div></td>";
    }

    private String sectionTitle(String title) {
        return "<div style=\"font-size:14px;font-weight:bold;color:#1f2d3d;margin-bottom:10px;\">"
             + esc(title) + "</div>";
    }

    private String summaryRows(String label, String value, boolean emphasize) {
        String valColor = emphasize ? "#1f2d3d" : "#37474f";
        String weight = emphasize ? "bold" : "normal";
        String fontSize = emphasize ? "17px" : "14px";
        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
             + "style=\"border-collapse:collapse;font-size:13px;\"><tr>"
             + "<td width=\"60%\" style=\"padding:8px 12px;border:1px solid #e3e8ee;color:#5b6b7c;\">"
             + esc(label) + "</td>"
             + "<td style=\"padding:8px 12px;border:1px solid #e3e8ee;color:" + valColor
             + ";font-weight:" + weight + ";font-size:" + fontSize + ";text-align:right;\">"
             + value + "</td></tr></table>";
    }

    private String kpi(int n) { return String.format("%,d", n); }

    private String blankToDash(String v) {
        return (v == null || v.isBlank() || v.equals("UNKNOWN")) ? "—" : v;
    }

    private String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private long resolveCreatedAt(Map<String, AttributeValue> item) {
        AttributeValue ca = item.get("createdAt");
        if (ca != null) {
            if (ca.n() != null) {
                try { return Long.parseLong(ca.n()); } catch (NumberFormatException ignored) {}
            }
            if (ca.s() != null) {
                // Historic rows stored an ISO-8601 string (Instant.toString()).
                try { return Instant.parse(ca.s()).getEpochSecond(); } catch (Exception ignored) {}
            }
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

    private String s(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return (v != null && v.s() != null) ? v.s() : "";
    }
}
