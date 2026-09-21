package com.invoice.processing;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.textract.model.S3Object;
import software.amazon.awssdk.services.textract.model.ThrottlingException;

public class InvoiceExtractionHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    // ── Configuration ──────────────────────────────────────────────────────────
    private static final String DYNAMO_TABLE         = System.getenv("DYNAMO_TABLE") != null
            ? System.getenv("DYNAMO_TABLE") : "invoices";
    private static final double CONFIDENCE_THRESHOLD = 95.0;

    // Allowed rounding difference between the sum of line items and the invoice total
    // before the invoice is treated as inconsistent and routed to human review.
    private static final double MATH_TOLERANCE = 0.5;

    // Loaded once from Secrets Manager (with env-var fallback)
    private final SecretsManagerConfig config = SecretsManagerConfig.getInstance();

    // ── AWS Clients ────────────────────────────────────────────────────────────
    private final ObjectMapper objectMapper  = new ObjectMapper();

    private final TextractClient textractClient = TextractClient.builder()
            .region(Region.AP_SOUTH_1).build();

    private final S3Client s3Client = S3Client.builder()
            .region(Region.AP_SOUTH_1).build();

    private final BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
            .region(Region.AP_SOUTH_1).build();

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
            .region(Region.AP_SOUTH_1).build();



    // ── Handler ────────────────────────────────────────────────────────────────
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            context.getLogger().log("EVENT = " + objectMapper.writeValueAsString(event));

            // 1. Extract S3 coordinates from EventBridge detail
            Map<String, Object> detail = (Map<String, Object>) event.get("detail");
            Map<String, Object> bucket = (Map<String, Object>) detail.get("bucket");
            Map<String, Object> object = (Map<String, Object>) detail.get("object");

            String bucketName = (String) bucket.get("name");
            String objectKey  = URLDecoder.decode((String) object.get("key"), StandardCharsets.UTF_8);


            context.getLogger().log("Bucket: " + bucketName + "  Key: " + objectKey);

            // 1b. Capture when the PDF was published to S3 (drives the dashboard "Uploaded" date)
            Instant uploadedAt = Instant.now();
            try {
                HeadObjectResponse head = s3Client.headObject(
                        HeadObjectRequest.builder().bucket(bucketName).key(objectKey).build());
                if (head.lastModified() != null) uploadedAt = head.lastModified();
            } catch (Exception e) {
                context.getLogger().log("WARN: could not read S3 lastModified: " + e.getMessage());
            }
            context.getLogger().log("Uploaded at (S3 lastModified): " + uploadedAt);

            // 2. Textract – AnalyzeExpense (with jittered backoff on rate limits)
            AnalyzeExpenseResponse textractResponse = analyzeExpenseWithRetry(bucketName, objectKey, context);

            InvoiceData invoiceData = extractInvoiceData(textractResponse, context);
            context.getLogger().log("Extracted: " + invoiceData);

            // 3. Capture individual confidence scores for logging and DynamoDB
            double avgConfidence   = computeAverageConfidence(invoiceData);
            double totalConfidence = invoiceData.getTotalConfidence() != null
                    ? invoiceData.getTotalConfidence()
                    : 0.0;

            context.getLogger().log("totalConfidence (TOTAL field): " + totalConfidence);
            context.getLogger().log("avgConfidence (all fields):    " + avgConfidence);

            // 4. Bedrock validation
            String invoiceJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(invoiceData);

            BedrockResult bedrockResult = invokeBedrockValidation(invoiceJson, context);
            String risk             = bedrockResult.risk;
            String validationStatus = bedrockResult.validationStatus;
            String comments         = bedrockResult.comments;
            // 5. Deterministic, rules-based validation.
            //    An invoice is auto-approved ONLY when it is complete, internally
            //    consistent, read with high confidence AND Bedrock agrees. Missing ANY
            //    field (including subtotal) or line items that do not add up to the
            //    total forces human review.
            List<String> missingFields = computeMissingFields(invoiceData, bedrockResult.missingFields);

            Double totalValue   = parseMoney(invoiceData.getTotal());
            Double lineItemsSum = invoiceData.getLineItemsSum();
            boolean mathMismatch = lineItemsSum != null && totalValue != null
                    && Math.abs(lineItemsSum - totalValue) > MATH_TOLERANCE;

            boolean lowConfidence = totalConfidence < CONFIDENCE_THRESHOLD;
            boolean bedrockReview = "REVIEW_REQUIRED".equals(bedrockResult.validationStatus);

            if (lowConfidence || !missingFields.isEmpty() || mathMismatch || bedrockReview) {
                validationStatus = "REVIEW_REQUIRED";

                List<String> reasons = new ArrayList<>();
                if (lowConfidence) {
                    reasons.add("low TOTAL confidence: "
                            + String.format("%.1f", totalConfidence) + "%");
                }
                if (!missingFields.isEmpty()) {
                    reasons.add("missing fields: " + String.join(", ", missingFields));
                }
                if (mathMismatch) {
                    reasons.add("line items sum (" + lineItemsSum
                            + ") != total (" + totalValue + ")");
                }

                if (bedrockReview) {
                    if (comments != null && !comments.isBlank()) {
                        reasons.add("AI flagged: " + comments);
                    }
                    comments = String.join(" | ", reasons);
                } else {
                    String reasonText = String.join(" | ", reasons);
                    comments = (comments == null || comments.isBlank())
                            ? reasonText : comments + " | " + reasonText;
                }

                context.getLogger().log("REVIEW_REQUIRED → " + comments);
            } else {
                validationStatus = "APPROVED";
                context.getLogger().log("APPROVED – complete, consistent, confident, AI agreed.");
            }

            // 6. Duplicate detection – must happen after Bedrock so we still know the duplicate
            boolean isDuplicate = false;
            if (invoiceData.getInvoiceId() != null && !invoiceData.getInvoiceId().isBlank()) {
                Map<String, AttributeValue> key = new HashMap<>();
                key.put("invoiceId", AttributeValue.builder()
                        .s(invoiceData.getInvoiceId()).build());

                GetItemResponse existing = dynamoDbClient.getItem(
                        GetItemRequest.builder().tableName(DYNAMO_TABLE).key(key).build());

                if (existing.hasItem()) {
                    isDuplicate    = true;
                    risk           = "HIGH";
                    validationStatus = "DUPLICATE";
                    comments       = "Duplicate invoice – already exists in the system.";
                    context.getLogger().log("DUPLICATE INVOICE DETECTED: " + invoiceData.getInvoiceId());
                }
            }

            // 7. Save to DynamoDB – skip when the invoiceId already exists so we never
            //    overwrite the original record (preserves the audit trail of the first decision)
            String invoiceId = resolveInvoiceId(invoiceData);
            if (!isDuplicate) {
                Map<String, AttributeValue> item = buildDynamoItem(
                        invoiceId, invoiceData, risk, validationStatus,
                        comments, missingFields, avgConfidence, uploadedAt);

                dynamoDbClient.putItem(PutItemRequest.builder()
                        .tableName(DYNAMO_TABLE).item(item).build());
                context.getLogger().log("Invoice saved to DynamoDB. ID=" + invoiceId
                        + "  status=" + validationStatus + "  risk=" + risk);

                // 8. Upload audit JSON to S3
                String auditKey = "audit/invoice-" + invoiceId.replace("#", "").trim() + ".json";
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucketName).key(auditKey).contentType("application/json").build(),
                        RequestBody.fromString(invoiceJson));
                context.getLogger().log("Audit JSON uploaded to: " + auditKey);
            } else {
                context.getLogger().log("Skipped persist of duplicate '" + invoiceId
                        + "' – original record kept");
            }

            // 9. Notification when review is required
            if ("REVIEW_REQUIRED".equals(validationStatus)) {
                sendReviewEmail(invoiceId, totalConfidence, avgConfidence, comments, context);
            }

            // 10. Return result to Step Functions
            Map<String, Object> result = new HashMap<>();
            result.put("validationStatus", validationStatus);   // APPROVED | REVIEW_REQUIRED | DUPLICATE
            result.put("risk", risk);
            result.put("invoiceId", invoiceId);
            result.put("totalConfidence", totalConfidence);     // TOTAL field confidence – drives routing
            result.put("avgConfidence", avgConfidence);         // average of all fields – informational
            result.put("comments", comments);
            result.put("missingFields", missingFields);
            return result;

        } catch (Exception e) {
            context.getLogger().log("FATAL ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Pull key fields from Textract AnalyzeExpense response. */
    private InvoiceData extractInvoiceData(AnalyzeExpenseResponse response, Context ctx) {
        InvoiceData data = new InvoiceData();

        for (var doc : response.expenseDocuments()) {
            for (var field : doc.summaryFields()) {
                String type  = field.type()           != null ? field.type().text()                 : "";
                String value = field.valueDetection() != null ? field.valueDetection().text()       : "";
                float  conf  = field.valueDetection() != null ? field.valueDetection().confidence() : 0f;

                ctx.getLogger().log("FIELD: " + type + " = " + value + " (" + conf + "%)");

                switch (type) {
                    case "VENDOR_NAME"           -> { data.setVendorName(value);   data.setVendorConfidence(conf); }
                    case "INVOICE_RECEIPT_DATE"  -> { data.setInvoiceDate(value);  data.setDateConfidence(conf);   }
                    case "INVOICE_RECEIPT_ID"    -> { data.setInvoiceId(value);    data.setInvoiceIdConfidence(conf); }
                    case "SUBTOTAL"              ->   data.setSubtotal(value);
                    case "TOTAL"                 -> { data.setTotal(value);         data.setTotalConfidence(conf);  }
                }
            }

            // Aggregate line-item amounts so the invoice total can be cross-checked.
            double lineSum   = 0;
            int    lineCount = 0;
            for (var group : doc.lineItemGroups()) {
                for (var item : group.lineItems()) {
                    Double price     = null;
                    Double unitPrice = null;
                    for (var f : item.lineItemExpenseFields()) {
                        String type = f.type()           != null ? f.type().text()           : "";
                        String val  = f.valueDetection() != null ? f.valueDetection().text() : "";
                        if ("PRICE".equals(type))           price     = parseMoney(val);
                        else if ("UNIT_PRICE".equals(type)) unitPrice = parseMoney(val);
                    }
                    Double amount = price != null ? price : unitPrice;
                    if (amount != null) {
                        lineSum += amount;
                        lineCount++;
                    }
                }
            }
            if (lineCount > 0) {
                data.setLineItemsSum(lineSum);
                data.setLineItemCount(lineCount);
                ctx.getLogger().log("LINE ITEMS: count=" + lineCount + "  sum=" + lineSum);
            }
        }
        return data;
    }

    /**
     * Compute the average of all confidence values that were actually detected.
     * Falls back to 0 if nothing was detected.
     */
    private double computeAverageConfidence(InvoiceData data) {
        List<Float> scores = new ArrayList<>();
        if (data.getVendorConfidence()    != null) scores.add(data.getVendorConfidence());
        if (data.getDateConfidence()      != null) scores.add(data.getDateConfidence());
        if (data.getInvoiceIdConfidence() != null) scores.add(data.getInvoiceIdConfidence());
        if (data.getTotalConfidence()     != null) scores.add(data.getTotalConfidence());
        if (scores.isEmpty()) return 0.0;
        return scores.stream().mapToDouble(Float::doubleValue).average().orElse(0.0);
    }

    /**
     * Calls Textract AnalyzeExpense with jittered exponential-backoff retries.
     *
     * AnalyzeExpense rate-limits aggressively under parallel load (HTTP 400
     * "Provisioned rate exceeded"). The SDK's built-in retry does not treat that
     * as retryable, so invoices could be dropped. We retry transparently up to
     * TEXTract_MAX_ATTEMPTS times before letting the exception propagate (where
     * EventBridge async retries can still pick it up).
     */
    private static final int TEXTRACT_MAX_ATTEMPTS = 5;

    private AnalyzeExpenseResponse analyzeExpenseWithRetry(String bucketName, String objectKey,
                                                           Context ctx) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return textractClient.analyzeExpense(
                        AnalyzeExpenseRequest.builder()
                                .document(Document.builder()
                                        .s3Object(S3Object.builder()
                                                .bucket(bucketName)
                                                .name(objectKey)
                                                .build())
                                        .build())
                                .build()
                );
            } catch (Exception e) {
                boolean transientFailure = e instanceof ThrottlingException
                        || e instanceof ProvisionedThroughputExceededException
                        || isRateLimitMessage(e);
                if (!transientFailure || attempt >= TEXTRACT_MAX_ATTEMPTS) {
                    throw e;
                }
                long backoff = (long) (500L * Math.pow(2, attempt - 1) * (0.7 + 0.6 * Math.random()));
                ctx.getLogger().log("Textract attempt " + attempt + " throttled (" + e.getMessage()
                        + ") – retrying in " + backoff + " ms");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private boolean isRateLimitMessage(Exception e) {
        if (e.getMessage() == null) return false;
        String m = e.getMessage().toLowerCase();
        return m.contains("rate exceeded") || m.contains("throttl");
    }

    /**
     * Build the list of fields that could not be read off the invoice. This is
     * computed from the extracted values themselves (not just Bedrock's opinion) so
     * a field is never silently treated as present. Missing ANY field forces review.
     */
    private List<String> computeMissingFields(InvoiceData data, List<String> bedrockMissing) {
        List<String> missing = new ArrayList<>();
        if (isBlank(data.getVendorName()))  missing.add("vendorName");
        if (isBlank(data.getInvoiceDate())) missing.add("invoiceDate");
        if (isBlank(data.getInvoiceId()))   missing.add("invoiceId");
        if (isBlank(data.getSubtotal()))    missing.add("subtotal");
        if (isBlank(data.getTotal()))       missing.add("total");

        // Fold in any recognised fields Bedrock also flagged (deduplicated).
        if (bedrockMissing != null) {
            for (String f : bedrockMissing) {
                if (f == null) continue;
                String t = f.trim();
                if (t.equalsIgnoreCase("vendorName")  && !missing.contains("vendorName"))  missing.add("vendorName");
                else if (t.equalsIgnoreCase("invoiceDate") && !missing.contains("invoiceDate")) missing.add("invoiceDate");
                else if (t.equalsIgnoreCase("invoiceId")   && !missing.contains("invoiceId"))   missing.add("invoiceId");
                else if (t.equalsIgnoreCase("subtotal")    && !missing.contains("subtotal"))    missing.add("subtotal");
                else if (t.equalsIgnoreCase("total")       && !missing.contains("total"))       missing.add("total");
            }
        }
        return missing;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Parse an amount such as "$1,234.56" or "5 999" into a Double. */
    private Double parseMoney(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || cleaned.equals("-") || cleaned.equals(".")) return null;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Call Bedrock Nova-Lite and parse the structured JSON response. */
    private BedrockResult invokeBedrockValidation(String invoiceJson, Context ctx) throws Exception {
        String prompt = """
You are an invoice validation assistant.

Analyze this invoice data:

%s

Return ONLY valid JSON – no markdown fences, no extra text.

{
  "risk": "LOW|MEDIUM|HIGH",
  "validationStatus": "APPROVED|REVIEW_REQUIRED",
  "missingFields": [],
  "comments": "short explanation"
}
""".formatted(invoiceJson);

        String requestBody = """
{
  "messages": [
    {
      "role": "user",
      "content": [{ "text": %s }]
    }
  ],
  "inferenceConfig": { "maxTokens": 500, "temperature": 0.2 }
}
""".formatted(objectMapper.writeValueAsString(prompt));

        InvokeModelResponse invokeResponse = bedrockClient.invokeModel(
                InvokeModelRequest.builder()
                        .modelId(config.getModelId())
                        .contentType("application/json")
                        .body(SdkBytes.fromUtf8String(requestBody))
                        .build());

        String rawResponse = invokeResponse.body().asUtf8String();
        ctx.getLogger().log("BEDROCK RAW RESPONSE: " + rawResponse);

        // ── Step 1: Unwrap Nova-Lite InvokeModel envelope ────────────────────
        // InvokeModel response structure:
        //   { "output": { "message": { "content": [{ "text": "<our JSON>" }] } } }
        String modelText = rawResponse;   // fallback: use the whole body
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            // Primary path for Nova-Lite / Nova-Pro via InvokeModel
            JsonNode textNode = root.at("/output/message/content/0/text");
            if (!textNode.isMissingNode() && !textNode.asText().isBlank()) {
                modelText = textNode.asText();
                ctx.getLogger().log("Unwrapped via /output/message/content/0/text");
            } else {
                // Some model versions put it at /content/0/text (Converse-style)
                JsonNode alt = root.at("/content/0/text");
                if (!alt.isMissingNode() && !alt.asText().isBlank()) {
                    modelText = alt.asText();
                    ctx.getLogger().log("Unwrapped via /content/0/text");
                }
                // else: leave modelText = rawResponse and try to parse it directly
            }
        } catch (Exception e) {
            ctx.getLogger().log("Could not parse outer envelope: " + e.getMessage()
                    + " – will try rawResponse directly");
        }

        ctx.getLogger().log("BEDROCK MODEL TEXT: " + modelText);

        // ── Step 2: Strip  fences and extract the JSON object ────────
        // Handles:  ```json\n{...}\n```  or  ```\n{...}\n```  or plain {…}
        // Also handles models that prefix with prose before the JSON block.
        String bedrockJson = extractJsonObject(modelText);
        ctx.getLogger().log("BEDROCK EXTRACTED JSON: " + bedrockJson);

        // ── Step 3: Parse the extracted JSON ────────────────────────────────
        BedrockResult result = new BedrockResult();
        try {
            JsonNode parsed = objectMapper.readTree(bedrockJson);

            result.risk             = textOrDefault(parsed, "risk",             "UNKNOWN");
            result.validationStatus = textOrDefault(parsed, "validationStatus", "UNKNOWN");
            result.comments         = textOrDefault(parsed, "comments",         "");

            // missingFields can be an array or a comma-separated string
            result.missingFields = new ArrayList<>();
            JsonNode mf = parsed.get("missingFields");
            if (mf != null && !mf.isNull()) {
                if (mf.isArray()) {
                    mf.forEach(n -> {
                        String val = n.asText().trim();
                        if (!val.isBlank()) result.missingFields.add(val);
                    });
                } else {
                    // Bedrock returned it as a plain string – split on comma
                    String raw = mf.asText().trim();
                    if (!raw.isBlank()) {
                        for (String part : raw.split(",")) {
                            String trimmed = part.trim();
                            if (!trimmed.isBlank()) result.missingFields.add(trimmed);
                        }
                    }
                }
            }

            ctx.getLogger().log("PARSED OK – risk=" + result.risk
                    + "  status=" + result.validationStatus
                    + "  comments=" + result.comments
                    + "  missingFields=" + result.missingFields);

        } catch (Exception e) {
            ctx.getLogger().log("JSON parse failed: " + e.getMessage()
                    + " – falling back to keyword scan on: " + modelText);

            // Last-resort keyword scan on the model's full text output
            result.risk = modelText.contains("HIGH")   ? "HIGH"
                        : modelText.contains("MEDIUM") ? "MEDIUM"
                        : modelText.contains("LOW")    ? "LOW"
                        : "UNKNOWN";

            result.validationStatus = modelText.contains("REVIEW_REQUIRED") ? "REVIEW_REQUIRED"
                                    : modelText.contains("APPROVED")        ? "APPROVED"
                                    : "UNKNOWN";

            // Try to extract comments text even without full JSON parse
            result.comments      = extractFieldValue(modelText, "comments");
            result.missingFields = new ArrayList<>();

            ctx.getLogger().log("FALLBACK – risk=" + result.risk
                    + "  status=" + result.validationStatus
                    + "  comments=" + result.comments);
        }

        return result;
    }

    /**
     * Robustly extract the first JSON object from a string that may contain:
     *  - Plain JSON:                  {"risk":"LOW",...}
     *  - Markdown fenced JSON:        ```json\n{...}\n```
     *  - Prose prefix + JSON:         "Here is the result:\n{...}"
     *  - JSON with trailing prose:    {...}\nDone.
     */
    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) return "{}";

        // 1. Strip ```json ... ``` or ``` ... ``` fences
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence    = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                t = t.substring(firstNewline + 1, lastFence).trim();
            }
        }

        // 2. Find the outermost { ... } in whatever remains
        int start = t.indexOf('{');
        int end   = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }

        // 3. Give up – return the trimmed text and let the caller fail gracefully
        return t;
    }

    /**
     * Last-resort: extract the string value of a JSON field by simple scanning.
     * Handles:  "comments": "some text here"
     * Only used in the fallback path when full JSON parsing has already failed.
     */
    private String extractFieldValue(String text, String fieldName) {
        if (text == null) return "";
        String marker = "\"" + fieldName + "\"";
        int fieldPos  = text.indexOf(marker);
        if (fieldPos < 0) return "";
        int colon      = text.indexOf(':', fieldPos + marker.length());
        if (colon < 0) return "";
        int openQuote  = text.indexOf('"', colon + 1);
        if (openQuote < 0) return "";
        // Walk forward respecting escaped quotes
        StringBuilder sb = new StringBuilder();
        for (int i = openQuote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '"') { sb.append('"'); i++; continue; }
                if (next == 'n') { sb.append('\n'); i++; continue; }
                if (next == '\\') { sb.append('\\'); i++; continue; }
            }
            if (c == '"') break;   // closing quote
            sb.append(c);
        }
        return sb.toString();
    }

    private String textOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull()) ? n.asText() : defaultValue;
    }

    /** Build the full DynamoDB item map. */
    private Map<String, AttributeValue> buildDynamoItem(
            String invoiceId, InvoiceData data,
            String risk, String validationStatus,
            String comments, List<String> missingFields,
            double avgConfidence, Instant createdAt) {

        Map<String, AttributeValue> item = new HashMap<>();

        item.put("invoiceId",         s(invoiceId));
        item.put("risk",              s(risk));
        item.put("validationStatus",  s(validationStatus));
        item.put("vendorName",        s(data.getVendorName()   != null ? data.getVendorName()   : "UNKNOWN"));
        item.put("invoiceDate",       s(data.getInvoiceDate()  != null ? data.getInvoiceDate()  : "UNKNOWN"));
        item.put("subtotal",          s(data.getSubtotal()     != null ? data.getSubtotal()     : "0"));
        item.put("total",             s(data.getTotal()        != null ? data.getTotal()        : "0"));
        item.put("comments",          s(comments != null ? comments : ""));
        item.put("missingFields",     s(missingFields != null ? String.join(", ", missingFields) : ""));
        item.put("avgConfidence",     n(avgConfidence));
        item.put("lineItemsSum",      n(data.getLineItemsSum()));
        item.put("lineItemCount",     n(data.getLineItemCount()));
        item.put("vendorConfidence",  n(data.getVendorConfidence()));
        item.put("totalConfidence",   n(data.getTotalConfidence()));
        item.put("invoiceIdConfidence", n(data.getInvoiceIdConfidence()));
        item.put("dateConfidence",    n(data.getDateConfidence()));
        item.put("createdAt",         s(createdAt != null ? createdAt.toString() : Instant.now().toString()));

        return item;
    }

    private String resolveInvoiceId(InvoiceData data) {
        String id = data.getInvoiceId();
        return (id == null || id.isBlank())
                ? "UNKNOWN-" + System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(1000, 9999)
                : id;
    }

    /** Send a review-required email via Brevo with one-click approve/reject links. */
    private void sendReviewEmail(String invoiceId, double totalConf,
                                 double avgConf, String comments, Context ctx) {
        try {
            SecretsManagerConfig cfg = SecretsManagerConfig.getInstance();

            // ── Generate approve/reject tokens (72-hour expiry) ────────────────
            long exp = Instant.now().getEpochSecond() + (72 * 60 * 60L);
            String approveToken = buildToken(invoiceId, "APPROVED", exp);
            String rejectToken  = buildToken(invoiceId, "REJECTED",  exp);

            String apiBase   = System.getenv("API_BASE_URL") != null
                    ? System.getenv("API_BASE_URL")
                    : "https://xi78f9b5fe.execute-api.ap-south-1.amazonaws.com";

            String approveLink = apiBase + "/invoices/approve?token=" + approveToken;
            String rejectLink  = apiBase + "/invoices/reject?token="  + rejectToken;
            String reviewUrl   = cfg.getFrontendUrl()
                    + "/review?id=" + invoiceId.replace("#", "%23").trim();

            String subject = "⚠️ Invoice Requires Manual Review – ID: " + invoiceId;
            String body = String.format(
                    "Hello,\n\n"
                  + "An invoice has been flagged for manual review.\n\n"
                  + "Invoice ID             : %s\n"
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
                    invoiceId, totalConf, CONFIDENCE_THRESHOLD, avgConf, comments,
                    approveLink, rejectLink, reviewUrl);

            BrevoMailer.send(cfg.getBrevoSender(), cfg.getSesReviewer(), subject, body);

            ctx.getLogger().log("Brevo review email with approval links sent for invoice "
                    + invoiceId + " to " + cfg.getSesReviewer());

        } catch (Exception e) {
            ctx.getLogger().log("WARNING: Failed to send review email: " + e.getMessage());
        }
    }

    /** Build a Base64URL-encoded token for one-click email approval. */
    private String buildToken(String invoiceId, String decision, long exp) {
        try {
            String json = objectMapper.writeValueAsString(
                    Map.of("invoiceId", invoiceId, "decision", decision, "exp", exp));
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    // ── DynamoDB value builders ────────────────────────────────────────────────

    private AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue n(Number value) {
        return AttributeValue.builder()
                .n(value != null ? String.valueOf(value) : "0")
                .build();
    }

    // ── Inner result holder ────────────────────────────────────────────────────

    private static class BedrockResult {
        String risk             = "UNKNOWN";
        String validationStatus = "UNKNOWN";
        String comments         = "";
        List<String> missingFields = new ArrayList<>();
    }
}
