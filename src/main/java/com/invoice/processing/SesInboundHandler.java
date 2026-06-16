package com.invoice.processing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * SesInboundHandler – triggered by SES Receipt Rule.
 *
 * Flow:
 *   1. SES receives email at invoices@zexxity.online
 *   2. SES stores raw email → s3://ses-inbound-emails-eu/ (eu-west-1)
 *   3. SES triggers this Lambda
 *   4. Lambda reads raw email from S3
 *   5. Lambda extracts PDF attachments
 *   6. Lambda saves each PDF → s3://invoice-processing-buckets/invoices/ (ap-south-1)
 *   7. EventBridge detects new file → Step Functions → full pipeline
 *
 * Environment variables (set in Lambda console):
 *   SES_INBOUND_BUCKET   – bucket where SES stores raw emails (eu-west-1)
 *   INVOICE_BUCKET       – your main invoice bucket (ap-south-1)
 *                          default: invoice-processing-buckets
 */
public class SesInboundHandler
        implements RequestHandler<Map<String, Object>, String> {

    // ── Buckets ────────────────────────────────────────────────────────────────
    private static final String SES_INBOUND_BUCKET = System.getenv("SES_INBOUND_BUCKET") != null
            ? System.getenv("SES_INBOUND_BUCKET")
            : "ses-inbound-emails-eu";

    private static final String INVOICE_BUCKET = System.getenv("INVOICE_BUCKET") != null
            ? System.getenv("INVOICE_BUCKET")
            : "invoice-processing-buckets";

    // ── S3 clients – two regions ───────────────────────────────────────────────
    // Raw emails are stored in eu-west-1 (where SES inbound works)
    private final S3Client s3EuWest = S3Client.builder()
            .region(Region.EU_WEST_1)
            .build();

    // Invoice PDFs go to ap-south-1 (where your pipeline runs)
    private final S3Client s3ApSouth = S3Client.builder()
            .region(Region.AP_SOUTH_1)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        try {
            context.getLogger().log("SesInbound EVENT: "
                    + objectMapper.writeValueAsString(event));

            // ── Extract SES message ID from the event ──────────────────────────
            // SES event structure: event.Records[0].ses.mail.messageId
            JsonNode root = objectMapper.valueToTree(event);
            JsonNode records = root.get("Records");

            if (records == null || !records.isArray() || records.isEmpty()) {
                context.getLogger().log("No Records in event – skipping");
                return "NO_RECORDS";
            }

            int processed = 0;

            for (JsonNode record : records) {
                String messageId = record.at("/ses/mail/messageId").asText();
                if (messageId == null || messageId.isBlank()) {
                    context.getLogger().log("No messageId found in record – skipping");
                    continue;
                }

                context.getLogger().log("Processing SES message: " + messageId);

                // ── Read raw email from S3 (eu-west-1) ────────────────────────
                byte[] rawEmail = readRawEmail(messageId, context);
                if (rawEmail == null) continue;

                // ── Parse MIME and extract PDF attachments ─────────────────────
                List<PdfAttachment> pdfs = extractPdfAttachments(rawEmail, context);
                context.getLogger().log("Found " + pdfs.size() + " PDF attachment(s)");

                if (pdfs.isEmpty()) {
                    context.getLogger().log(
                            "No PDF attachments in message " + messageId + " – skipping");
                    continue;
                }

                // ── Save each PDF to invoice bucket (ap-south-1) ───────────────
                for (PdfAttachment pdf : pdfs) {
                    String objectKey = "invoices/" + UUID.randomUUID() + "-" + pdf.fileName;

                    s3ApSouth.putObject(
                            PutObjectRequest.builder()
                                    .bucket(INVOICE_BUCKET)
                                    .key(objectKey)
                                    .contentType("application/pdf")
                                    .metadata(Map.of(
                                            "source",      "email",
                                            "messageId",   messageId,
                                            "originalName", pdf.fileName
                                    ))
                                    .build(),
                            RequestBody.fromBytes(pdf.data));

                    context.getLogger().log("Saved PDF to s3://"
                            + INVOICE_BUCKET + "/" + objectKey
                            + " (" + pdf.data.length + " bytes)");
                    processed++;
                }
            }

            String result = "Processed " + processed + " PDF attachment(s)";
            context.getLogger().log(result);
            return result;

        } catch (Exception e) {
            context.getLogger().log("SesInbound FATAL ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ── Read raw email bytes from S3 ───────────────────────────────────────────

    private byte[] readRawEmail(String messageId, Context ctx) {
        try {
            // SES stores raw emails under the prefix you configure in the receipt rule.
            // Default path: <prefix>/<messageId>  (no file extension)
            String key = "emails/" + messageId;

            ctx.getLogger().log("Reading raw email from s3://"
                    + SES_INBOUND_BUCKET + "/" + key);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            s3EuWest.getObject(
                    GetObjectRequest.builder()
                            .bucket(SES_INBOUND_BUCKET)
                            .key(key)
                            .build(),
                    ResponseTransformer.toOutputStream(out));

            return out.toByteArray();

        } catch (Exception e) {
            ctx.getLogger().log("Could not read raw email: " + e.getMessage());
            return null;
        }
    }

    // ── Parse MIME message and collect PDF attachments ─────────────────────────

    private List<PdfAttachment> extractPdfAttachments(byte[] rawEmail, Context ctx)
            throws MessagingException, IOException {

        List<PdfAttachment> results = new ArrayList<>();

        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage message = new MimeMessage(session,
                new ByteArrayInputStream(rawEmail));

        Object content = message.getContent();

        if (content instanceof MimeMultipart multipart) {
            extractFromMultipart(multipart, results, ctx);
        } else {
            ctx.getLogger().log("Email content is not multipart – no attachments");
        }

        return results;
    }

    private void extractFromMultipart(MimeMultipart multipart,
                                      List<PdfAttachment> results,
                                      Context ctx)
            throws MessagingException, IOException {

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String disposition = part.getDisposition();
            String contentType = part.getContentType().toLowerCase();

            ctx.getLogger().log("Part " + i
                    + " disposition=" + disposition
                    + " contentType=" + contentType);

            // Recurse into nested multipart (e.g. multipart/mixed inside multipart/alternative)
            if (contentType.startsWith("multipart/")) {
                extractFromMultipart((MimeMultipart) part.getContent(), results, ctx);
                continue;
            }

            // Collect PDF attachments
            boolean isPdf = contentType.contains("pdf")
                    || contentType.contains("octet-stream");
            boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
                    || Part.INLINE.equalsIgnoreCase(disposition);

            if (isPdf && isAttachment) {
                String fileName = part.getFileName();
                if (fileName == null) fileName = "invoice-" + UUID.randomUUID() + ".pdf";
                // Sanitise filename
                fileName = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
                if (!fileName.toLowerCase().endsWith(".pdf")) fileName += ".pdf";

                InputStream is = part.getInputStream();
                byte[] data = is.readAllBytes();
                is.close();

                ctx.getLogger().log("Extracted PDF: " + fileName
                        + " (" + data.length + " bytes)");
                results.add(new PdfAttachment(fileName, data));
            }
        }
    }

    // ── Simple data holder ─────────────────────────────────────────────────────

    private static class PdfAttachment {
        final String fileName;
        final byte[] data;
        PdfAttachment(String fileName, byte[] data) {
            this.fileName = fileName;
            this.data     = data;
        }
    }
}
