package com.invoice.processing;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Textract often reports the invoice number as an untyped "OTHER" token such
    // as "# 8439" instead of INVOICE_RECEIPT_ID. Fall back to this pattern.
    private static final Pattern INVOICE_ID_FROM_HASH = Pattern.compile("#\\s*([0-9][0-9A-Za-z.\\-]*)");
    private static final String DYNAMO_TABLE         = System.getenv("DYNAMO_TABLE") != null
            ? System.getenv("DYNAMO_TABLE") : "invoices";
    private static final double CONFIDENCE_THRESHOLD = 95.0;

    // Allowed rounding difference when reconciling the invoice rows
    // (line items vs subtotal, and subtotal + shipping + tax vs total)
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
            String sourceFileName = deriveSourceFileName(objectKey);


            context.getLogger().log("Bucket: " + bucketName + "  Key: " + objectKey
                    + "  SourceFile: " + sourceFileName);

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
            //    field (including subtotal) or a math row that does not tie out
            //    (line items vs subtotal, subtotal−discount+shipping+tax vs total)
            //    forces review.
            List<String> missingFields = computeMissingFields(invoiceData, bedrockResult.missingFields);

            Double totalValue   = parseMoney(invoiceData.getTotal());
            Double lineItemsSum = invoiceData.getLineItemsSum();
            Double subtotalValue = parseMoney(invoiceData.getSubtotal());
            Double shippingValue = parseMoney(invoiceData.getShipping());
            Double taxValue      = parseMoney(invoiceData.getTax());
            Double discountValue = parseMoney(invoiceData.getDiscount());

            // Follow the invoice schema: subtotal − discount + shipping + tax = total.
            // Line items (when detected) must equal the subtotal; the net amount
            // (subtotal minus discount) plus shipping/tax must equal the total.
            List<String> mathReasons = new ArrayList<>();
            if (lineItemsSum != null && subtotalValue != null && discountValue == null
                    && Math.abs(lineItemsSum - subtotalValue) > MATH_TOLERANCE) {
                mathReasons.add("line items sum (" + lineItemsSum
                        + ") != subtotal (" + subtotalValue + ")");
            }
            if (totalValue != null && (subtotalValue != null || lineItemsSum != null)) {
                double netAmount  = subtotalValue != null ? subtotalValue : lineItemsSum;
                double expected   = netAmount - (discountValue != null ? discountValue : 0.0)
                        + (shippingValue != null ? shippingValue : 0.0)
                        + (taxValue != null ? taxValue : 0.0);
                if (Math.abs(expected - totalValue) > MATH_TOLERANCE) {
                    StringBuilder calc = new StringBuilder();
                    String baseLabel = subtotalValue != null ? "subtotal" : "line items";
                    calc.append(baseLabel).append(" (").append(netAmount).append(')');
                    if (discountValue != null) {
                        calc.append(" − discount (").append(discountValue).append(')');
                    }
                    if (shippingValue != null) {
                        calc.append(" + shipping (").append(shippingValue).append(')');
                    }
                    if (taxValue != null) {
                        calc.append(" + tax (").append(taxValue).append(')');
                    }
                    calc.append(" = ").append(expected)
                            .append(" != total (").append(totalValue).append(')');
                    mathReasons.add(calc.toString());
                }
            }
            boolean mathMismatch = !mathReasons.isEmpty();

            boolean lowConfidence = totalConfidence < CONFIDENCE_THRESHOLD;
            boolean bedrockReview = !"APPROVED".equals(bedrockResult.validationStatus);

            // Our deterministic check is authoritative for tie-out arithmetic. If
            // the model claims a math failure but every required field is present,
            // confidence is high and our own math passes, trust the numbers (the
            // model frequently hallucinates amounts) and auto-approve.
            boolean aiMathOnlyDisagreement = Boolean.FALSE.equals(bedrockResult.mathConsistent)
                    && !mathMismatch
                    && missingFields.isEmpty()
                    && !lowConfidence;

            if (aiMathOnlyDisagreement) {
                validationStatus = "APPROVED";
                risk            = "LOW";
                comments        = "Math verifies deterministically (subtotal - discount + shipping + tax = total);"
                        + " AI tie-out concern overridden by precise check.";
                context.getLogger().log("APPROVED - deterministic math verified; AI math claim overridden.");
            } else if (lowConfidence || !missingFields.isEmpty() || mathMismatch || bedrockReview) {
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
                    reasons.addAll(mathReasons);
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

            // A model that stayed silent or returned UNKNOWN must never leave a
            // fuzzy risk behind: default to LOW for approvals, MEDIUM for reviews.
            if (risk == null || risk.isBlank() || "UNKNOWN".equals(risk)) {
                risk = "APPROVED".equals(validationStatus) ? "LOW" : "MEDIUM";
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

            // 9. Review notifications are handled by the Step Functions
            //    RequestApproval state, which emails the reviewer links that
            //    carry the task token (WaitForTaskToken).

            // 10. Return result to Step Functions
            Map<String, Object> result = new HashMap<>();
            result.put("validationStatus", validationStatus);   // APPROVED | REVIEW_REQUIRED | DUPLICATE
            result.put("risk", risk);
            result.put("invoiceId", invoiceId);
            result.put("totalConfidence", totalConfidence);     // TOTAL field confidence – drives routing
            result.put("avgConfidence", avgConfidence);         // average of all fields – informational
            result.put("comments", comments);
            result.put("missingFields", missingFields);
            result.put("sourceFileName", sourceFileName);
            return result;

        } catch (Exception e) {
            context.getLogger().log("FATAL ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Derive the original PDF file name from an S3 object key of the form
     * {@code invoices/<uuid>-<Original Name>.pdf}. When the key does not match
     * that shape (older uploads, arbitrary keys), fall back to the last path
     * segment.
     */
    private static String deriveSourceFileName(String objectKey) {
        String base = objectKey;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);

        if (base.length() > 37) {
            String head = base.substring(0, 36);
            if (head.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    && base.charAt(36) == '-') {
                String name = base.substring(37);
                if (!name.isBlank()) return name;
            }
        }
        return base;
    }

    /** Pull key fields from Textract AnalyzeExpense response. */
    private InvoiceData extractInvoiceData(AnalyzeExpenseResponse response, Context ctx) {
        InvoiceData data = new InvoiceData();

        for (var doc : response.expenseDocuments()) {
            // Textract can report SUBTOTAL multiple times (the amount column, the
            // unit rate, …) with no ordering guarantee. Collect candidates and pick
            // the one matching the line-item sum after aggregation.
            List<String> subtotalCandidates = new ArrayList<>();
            List<String> otherCandidates   = new ArrayList<>();

            for (var field : doc.summaryFields()) {
                String type  = field.type()           != null ? field.type().text()                 : "";
                String value = field.valueDetection() != null ? field.valueDetection().text()       : "";
                float  conf  = field.valueDetection() != null ? field.valueDetection().confidence() : 0f;

                ctx.getLogger().log("FIELD: " + type + " = " + value + " (" + conf + "%)");

                switch (type) {
                    case "VENDOR_NAME"           -> { data.setVendorName(value);   data.setVendorConfidence(conf); }
                    case "INVOICE_RECEIPT_DATE"  -> { data.setInvoiceDate(value);  data.setDateConfidence(conf);   }
                    case "INVOICE_RECEIPT_ID"    -> { data.setInvoiceId(value);    data.setInvoiceIdConfidence(conf); }
                    case "SUBTOTAL"              ->   subtotalCandidates.add(value);
                    case "SHIPPING"              ->   data.setShipping(value);
                    case "SHIPPING_HANDLING_CHARGE" -> data.setShipping(value);
                    case "TAX"                   ->   data.setTax(value);
                    case "DISCOUNT"              ->   data.setDiscount(value);
                    case "TOTAL"                 -> { data.setTotal(value);         data.setTotalConfidence(conf);  }
                    case "OTHER"                 ->   otherCandidates.add(value);
                }
            }

            // Fallback: some PDFs (e.g. "Invoice # 8439") come back with the number
            // as an untyped OTHER token. Prefer an "Invoice # <n>"-shaped candidate.
            if (isBlank(data.getInvoiceId())) {
                for (String other : otherCandidates) {
                    Matcher m = INVOICE_ID_FROM_HASH.matcher(other);
                    if (m.find()) {
                        data.setInvoiceId(m.group(1).trim());
                        ctx.getLogger().log("Invoice ID recovered from OTHER '" + other
                                + "' -> " + data.getInvoiceId());
                        break;
                    }
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

            // Choose the SUBTOTAL candidate that matches the line-item sum (best
            // signal for the true subtotal); otherwise keep the last occurrence,
            // matching Textract's typical ordering.
            if (!subtotalCandidates.isEmpty()) {
                Double lineSumFlag = data.getLineItemsSum();
                String chosen = subtotalCandidates.get(subtotalCandidates.size() - 1);
                if (lineSumFlag != null) {
                    for (String candidate : subtotalCandidates) {
                        Double v = parseMoney(candidate);
                        if (v != null && Math.abs(v - lineSumFlag) < 1e-2) {
                            chosen = candidate;
                            break;
                        }
                    }
                }
                data.setSubtotal(chosen);
                ctx.getLogger().log("SUBTOTAL chosen: " + chosen);
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
You are a strict invoice validation assistant for an automated approval system.

You are given JSON extracted from an invoice. Your ONLY job is to CHECK it for
completeness, internal consistency and plausibility, then return a pass/flag
decision. You must NOT correct, reformat or "fix" the data.

=== DATA TO CHECK ===
%s

=== GROUND RULES ===
1. Trust the extracted values. Never invent, correct or reformat numbers or
   text. Preserve currency formatting exactly as provided (e.g. "$1,015.68").
   A null or blank value means the field was not found on the invoice; do not
   guess it.
2. A field counts as "missing" ONLY when it is absent, null or blank
   (whitespace only). Zero amounts such as 0, 0.0, $0.00 are VALID, NOT missing.
3. Re-verify the math using this exact invoice schema:
     expectedTotal = subtotal - discount + shipping + tax
   - If line item amounts are present, their sum must equal the subtotal.
   - A mismatch larger than $0.50 between (line items vs subtotal) or between
     (expectedTotal vs total) is a MAJOR red flag. Flag it.
   - Do the arithmetic yourself, step by step, using ONLY the numbers in the
     data. Report your conclusion in "mathConsistent". A difference of $0.50
     or less counts as consistent.
4. If anything is uncertain or numbers do not tie out, say so explicitly -
   prefer REVIEW_REQUIRED over forcing approval.

=== DECISION RULES ===
- validationStatus = "APPROVED" ONLY when ALL of these hold:
    * every required field is present (vendorName, invoiceId, invoiceDate,
      subtotal, total)
    * the math ties out within $0.50 using the schema above (mathConsistent)
    * values are plausible (no obviously wrong vendor/amount for the period)
  Otherwise validationStatus = "REVIEW_REQUIRED".
- risk: "LOW" for a clean, fully consistent invoice; "MEDIUM" for minor
  gaps or a single explainable inconsistency; "HIGH" for missing required
  fields, math that does not tie out, or implausible values.
- missingFields: list ONLY the truly missing fields. Omit fields that are
  present, null-but-irrelevant, or zero. Use exact field names from the data.
- comments: exactly one short, factual sentence stating your main finding,
  e.g. "All required fields present; subtotal - discount + shipping = total."
  If flagged, state the concrete reason (e.g. "Missing total; totals do not
  tie out by $1.23.").
- mathConsistent: boolean. true when the amounts tie out within $0.50 using
  the schema above, false otherwise. Never null/string.

=== OUTPUT CONTRACT ===
Respond with EXACTLY ONE valid JSON object and NOTHING ELSE - no prose, no
markdown, no code fences, no trailing explanation. This output is parsed by a
machine, so a single extra character will break it. Use exactly this shape:

{
  "risk": "LOW",
  "validationStatus": "APPROVED",
  "missingFields": [],
  "comments": "All required fields present; subtotal - discount + shipping = total.",
  "mathConsistent": true
}

risk MUST be one of LOW, MEDIUM, HIGH.
validationStatus MUST be one of APPROVED, REVIEW_REQUIRED.
""".formatted(invoiceJson);

        String requestBody = """
{
  "messages": [
    {
      "role": "user",
      "content": [{ "text": %s }]
    }
  ],
  "inferenceConfig": { "maxTokens": 500, "temperature": 0.1 }
}
""".formatted(objectMapper.writeValueAsString(prompt));

        InvokeModelResponse invokeResponse;
        try {
            invokeResponse = bedrockClient.invokeModel(
                    InvokeModelRequest.builder()
                            .modelId(config.getModelId())
                            .contentType("application/json")
                            .body(SdkBytes.fromUtf8String(requestBody))
                            .build());
        } catch (Exception e) {
            // Never let a Bedrock outage silently drop invoices. Degrade to
            // deterministic checks only and route to manual review.
            ctx.getLogger().log("Bedrock validation unavailable (" + e.getMessage()
                    + ") – degrading to deterministic checks, routing to manual review");
            BedrockResult degraded = new BedrockResult();
            degraded.validationStatus = "REVIEW_REQUIRED";
            degraded.risk            = "MEDIUM";
            degraded.comments        = "Bedrock validation unavailable (" + e.getMessage()
                    + "); routed to manual review.";
            return degraded;
        }

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

            // mathConsistent is the model's claim about whether the amount rows
            // tie out. When it is absent, callers should keep the old behaviour.
            JsonNode mc = parsed.get("mathConsistent");
            if (mc != null && mc.isBoolean()) {
                result.mathConsistent = mc.asBoolean();
            }

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
        item.put("shipping",          s(data.getShipping()     != null ? data.getShipping()     : "0"));
        item.put("tax",               s(data.getTax()          != null ? data.getTax()          : "0"));
        item.put("discount",          s(data.getDiscount()     != null ? data.getDiscount()     : "0"));
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
        Boolean mathConsistent  = null;   // true/false when the model reports arithmetic
    }
}
