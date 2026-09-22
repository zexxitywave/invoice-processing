package com.invoice.processing;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RequestApprovalHandler – invoked by Step Functions with a task token when an
 * invoice requires human review (WaitForTaskToken callback pattern).
 *
 * It emails the reviewer one-click Approve/Reject links that carry the task
 * token, so the reviewer's decision resumes the state machine. The links are
 * handled by TokenApprovalHandler, which writes the decision to DynamoDB and
 * then calls SendTaskSuccess to complete the waiting state.
 */
public class RequestApprovalHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final double CONFIDENCE_THRESHOLD = 95.0;
    private static final long   TOKEN_TTL_SECONDS    = 72 * 60 * 60L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("RequestApproval EVENT: " + safeJson(event));

        try {
            String taskToken = str(event.get("taskToken"));
            String invoiceId = str(event.get("invoiceId"));
            String risk       = str(event.get("risk"));
            String comments   = str(event.get("comments"));
            String sourceFile = str(event.get("sourceFileName"));
            double totalConf  = num(event.get("totalConfidence"));
            double avgConf    = num(event.get("avgConfidence"));

            if (taskToken.isBlank() || invoiceId.isBlank()) {
                throw new IllegalArgumentException("taskToken and invoiceId are required");
            }

            long exp = Instant.now().getEpochSecond() + TOKEN_TTL_SECONDS;
            String approveToken = buildToken(invoiceId, "APPROVED", exp, taskToken);
            String rejectToken  = buildToken(invoiceId, "REJECTED",  exp, taskToken);

            String apiBase = System.getenv("API_BASE_URL") != null
                    ? System.getenv("API_BASE_URL")
                    : "";
            String approveLink = apiBase + "/invoices/approve?token=" + approveToken;
            String rejectLink  = apiBase + "/invoices/reject?token="  + rejectToken;

            SecretsManagerConfig cfg = SecretsManagerConfig.getInstance();
            String reviewUrl = cfg.getFrontendUrl() + "/review?id="
                    + invoiceId.replace("#", "%23").trim();

            String subject = "⚠️ Invoice Requires Manual Review – " + invoiceId + " (" + sourceFile + ")";
            String body = String.format(
                    "Hello,\n\n"
                  + "An invoice has been flagged for manual review.\n\n"
                  + "Uploaded PDF file      : %s\n"
                  + "Invoice ID             : %s\n"
                  + "Risk level             : %s\n"
                  + "TOTAL field confidence : %.1f%%  (threshold: %.1f%%)\n"
                  + "Average confidence     : %.1f%%\n"
                  + "Comments               : %s\n\n"
                  + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                  + "ONE-CLICK DECISION (no login required):\n\n"
                  + "✅ APPROVE this invoice:\n%s\n\n"
                  + "❌ REJECT this invoice:\n%s\n\n"
                  + "⏰ Links expire in 72 hours.\n"
                  + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                  + "Or review with full details in the dashboard:\n%s\n\n"
                  + "— Invoice Processing System",
                    sourceFile, invoiceId, risk, totalConf, CONFIDENCE_THRESHOLD, avgConf, comments,
                    approveLink, rejectLink, reviewUrl);

            try {
                BrevoMailer.send(cfg.getBrevoSender(), cfg.getSesReviewer(), subject, body);
                context.getLogger().log("Review email (with task token) sent for "
                        + invoiceId + " to " + cfg.getSesReviewer());
            } catch (Exception e) {
                // Never fail the state machine because one email could not be
                // sent – the dashboard and nightly digest still surface it.
                context.getLogger().log("WARNING: review email failed: " + e.getMessage());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "OK");
            result.put("invoiceId", invoiceId);
            return result;

        } catch (Exception e) {
            context.getLogger().log("RequestApproval ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private String buildToken(String invoiceId, String decision, long exp, String taskToken) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "invoiceId", invoiceId,
                    "decision",  decision,
                    "exp",       exp,
                    "taskToken", taskToken));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }

    private double num(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }

    private String safeJson(Object event) {
        try { return objectMapper.writeValueAsString(event); }
        catch (Exception e) { return String.valueOf(event); }
    }
}