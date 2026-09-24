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

/**
 * ApproveRejectHandler – called by API Gateway when the reviewer clicks
 * [Approve] or [Reject] in the Amplify UI.
 *
 * DynamoDB write and email send run in parallel via CompletableFuture.
 * Lambda waits for BOTH to complete before returning — so the email is
 * guaranteed to be dispatched while keeping total latency low (both
 * operations run concurrently instead of sequentially).
 */
public class ApproveRejectHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String DYNAMO_TABLE = System.getenv("DYNAMO_TABLE") != null
            ? System.getenv("DYNAMO_TABLE") : "invoices";

    // Built eagerly at handler init so both SDK stacks are captured in the
    // SnapStart snapshot; restored environments reuse these fully-formed clients.
    private static final DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
            .region(Region.AP_SOUTH_1).build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            long start = System.currentTimeMillis();
            context.getLogger().log("⏱ [TIMING] ApproveRejectHandler started");
            context.getLogger().log("ApproveReject EVENT: " + objectMapper.writeValueAsString(event));

            // ── Warm-up ping ──────────────────────────────────────────────────
            // The scheduled lambda-warm invokes this alias with {"warmup":true}.
            // Touch DynamoDB so the SDK's HTTP connection pool is primed; otherwise
            // the first real review pays the ~3-4s connection-setup cost.
            if (Boolean.TRUE.equals(event.get("warmup"))) {
                dynamoDbClient.describeTable(b -> b.tableName(DYNAMO_TABLE));
                context.getLogger().log("WARMUP: DynamoDB connection primed");
                Map<String, Object> warm = new HashMap<>();
                warm.put("statusCode", 200);
                warm.put("body", "{\"warmup\":true}");
                return warm;
            }

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
                                "SET reviewDecision     = :status, "
                              + "    validationStatus  = :status, "
                              + "    reviewedAt        = :reviewedAt, "
                              + "    reviewedBy        = :reviewedBy, "
                              + "    reviewNote        = :reason")
                        .expressionAttributeValues(vals)
                        .build());

                context.getLogger().log("⏱ [TIMING] DynamoDB updateItem took: "
                        + (System.currentTimeMillis() - start) + " ms");
            });

            CompletableFuture<Void> sesFuture = CompletableFuture.runAsync(() -> {
                try {
                    sendConfirmationEmail(fInvoiceId, fDecision, fReviewer, fReason, context);
                    context.getLogger().log("⏱ [TIMING] confirmation email sent, elapsed: "
                            + (System.currentTimeMillis() - start) + " ms");
                } catch (Exception e) {
                    context.getLogger().log("WARNING: confirmation email failed: " + e.getMessage());
                }
            });

            // Wait for BOTH DynamoDB AND the email send to complete before returning.
            // SES must finish before Lambda returns — otherwise the execution
            // environment freezes and the async thread never completes.
            CompletableFuture.allOf(dynamoFuture, sesFuture).get(15, TimeUnit.SECONDS);

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
            String frontendUrl = cfg.getFrontendUrl();

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
                    frontendUrl);

            String html = buildConfirmationHtml(invoiceId, decision, reviewer, reason, frontendUrl);

            // Send the confirmation to whoever the reviewer typed (their own email),
            // falling back to the configured reviewer address only when blank.
            String reviewerEmail = reviewer != null && !reviewer.isBlank()
                    ? reviewer.trim() : cfg.getSesReviewer();

            BrevoMailer.send(cfg.getBrevoSender(), reviewerEmail, subject, body, html);
            ctx.getLogger().log("Confirmation email sent to " + reviewerEmail);

            ctx.getLogger().log("Confirmation email sent for " + invoiceId + " → " + decision);

        } catch (Exception e) {
            // Don't fail the whole request if email fails
            ctx.getLogger().log("WARNING: confirmation email failed: " + e.getMessage());
        }
    }

    // ── Styled HTML confirmation email ─────────────────────────────────────────

    private static String buildConfirmationHtml(String invoiceId, String decision,
                                                String reviewer, String reason, String frontendUrl) {
        boolean approved = "APPROVED".equals(decision);
        String  emoji    = approved ? "✅" : "❌";

        String headerBg  = approved ? "linear-gradient(135deg,#12b76a,#0e8f57)"
                                    : "linear-gradient(135deg,#d92d20,#b42318)";
        String badgeBg   = approved ? "#ecfdf3" : "#fef3f2";
        String badgeTxt  = approved ? "#067647" : "#b42318";

        String safeId       = esc(invoiceId);
        String safeReviewer = esc(reviewer != null ? reviewer : "unknown");
        String safeReason   = esc(reason != null ? reason : "—").replace("\n", "<br/>");
        String safeUrl      = esc(frontendUrl);
        String safeDecision = esc(decision);

        return "<div style=\"background:#f2f4f7;padding:24px 12px;font-family:'Segoe UI',Arial,Helvetica,sans-serif;\">"
             + "<div style=\"max-width:500px;margin:0 auto;background:#ffffff;border-radius:14px;overflow:hidden;"
             +     "border:1px solid #e4e7ec;box-shadow:0 6px 20px rgba(16,24,40,.08);\">"
             // Header
             + "<div style=\"padding:22px 28px;background:" + headerBg + ";\">"
             +   "<div style=\"font-size:12px;letter-spacing:1px;opacity:.85;\">INVOICE PROCESSING SYSTEM</div>"
             +   "<div style=\"font-size:22px;font-weight:700;color:#ffffff;margin-top:6px;\">"
             +      emoji + " Invoice " + safeDecision + "</div>"
             + "</div>"
             // Body card
             + "<div style=\"padding:24px 28px;\">"
             +   "<div style=\"margin-bottom:18px;font-size:13px;color:#475467;line-height:20px;\">"
             +      "Hello,&nbsp; your decision has been recorded. Here are the details:</div>"
             // Decision badge
             +   "<div style=\"margin-bottom:22px;display:inline-block;padding:8px 18px;border-radius:999px;"
             +      "background:" + badgeBg + ";color:" + badgeTxt + ";font-size:13px;font-weight:700;"
             +      "letter-spacing:.5px;\">" + emoji + " " + safeDecision + "</div>"
             // Detail list
             +   "<table role=\"presentation\" style=\"width:100%;border-collapse:collapse;font-size:13.5px;\">"
             +     "<tr>"
             +       "<td style=\"padding:9px 0;color:#667085;width:40%;\">Invoice ID</td>"
             +       "<td style=\"padding:9px 0;font-weight:600;color:#101828;\"># " + safeId + "</td>"
             +     "</tr>"
             +     "<tr>"
             +       "<td style=\"padding:9px 0;color:#667085;width:40%;border-top:1px solid #f2f4f7;\">Decision</td>"
             +       "<td style=\"padding:9px 0;font-weight:600;color:#101828;border-top:1px solid #f2f4f7;\">"
             +          safeDecision + "</td>"
             +     "</tr>"
             +     "<tr>"
             +       "<td style=\"padding:9px 0;color:#667085;width:40%;border-top:1px solid #f2f4f7;\">Reviewed by</td>"
             +       "<td style=\"padding:9px 0;font-weight:600;color:#101828;border-top:1px solid #f2f4f7;\">"
             +          safeReviewer + "</td>"
             +     "</tr>"
             +     "<tr>"
             +       "<td style=\"padding:9px 0;color:#667085;width:40%;border-top:1px solid #f2f4f7;\">Note</td>"
             +       "<td style=\"padding:9px 0;color:#101828;border-top:1px solid #f2f4f7;\">" + safeReason + "</td>"
             +     "</tr>"
             +     "<tr>"
             +       "<td style=\"padding:9px 0;color:#667085;width:40%;border-top:1px solid #f2f4f7;\">Timestamp</td>"
             +       "<td style=\"padding:9px 0;color:#101828;border-top:1px solid #f2f4f7;\">" + Instant.now() + "</td>"
             +     "</tr>"
             +   "</table>"
             +   "<div style=\"margin-top:18px;padding:14px 16px;background:#f9fafb;border-radius:10px;"
             +      "font-size:12px;color:#475467;line-height:18px;\">"
             +      "This action has been recorded in the system and will reflect on the dashboard.</div>"
             + "</div>"
             // Footer with CTA
             + "<div style=\"padding:18px 28px;border-top:1px solid #e4e7ec;text-align:center;\">"
             +   "<a href=\"" + safeUrl + "\" style=\"display:inline-block;background:#2e5cff;color:#ffffff;"
             +      "text-decoration:none;padding:11px 22px;border-radius:8px;font-size:13px;font-weight:600;\">"
             +      "View Dashboard →</a>"
             +   "<div style=\"margin-top:12px;font-size:11px;color:#98a2b3;\">"
             +      "— Invoice Processing System · zexxity</div>"
             + "</div>"
             + "</div></div>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
