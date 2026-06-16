package com.invoice.processing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3CleanupHandler – runs every Sunday at 02:00 UTC via EventBridge.
 *
 * Deletes PDF files older than 30 days from:
 *   s3://invoice-processing-buckets/invoices/
 *
 * Audit JSON files (audit/) are intentionally kept as the permanent record.
 * Only the raw PDFs are cleaned up to save storage cost.
 */
public class S3CleanupHandler
        implements RequestHandler<Map<String, Object>, String> {

    private static final String INVOICE_BUCKET  = System.getenv("INVOICE_BUCKET") != null
            ? System.getenv("INVOICE_BUCKET") : "invoice-processing-buckets";
    private static final String INVOICE_PREFIX  = "invoices/";
    private static final int    RETENTION_DAYS  = 30;

    private final S3Client s3 = S3Client.builder()
            .region(Region.AP_SOUTH_1).build();

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("S3Cleanup: starting — bucket=" + INVOICE_BUCKET
                + "  prefix=" + INVOICE_PREFIX
                + "  retentionDays=" + RETENTION_DAYS);

        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        List<ObjectIdentifier> toDelete = new ArrayList<>();
        String continuationToken = null;

        // ── List all objects under invoices/ ──────────────────────────────────
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                    .bucket(INVOICE_BUCKET)
                    .prefix(INVOICE_PREFIX);
            if (continuationToken != null) req.continuationToken(continuationToken);

            ListObjectsV2Response resp = s3.listObjectsV2(req.build());

            for (S3Object obj : resp.contents()) {
                if (obj.lastModified().isBefore(cutoff)) {
                    toDelete.add(ObjectIdentifier.builder().key(obj.key()).build());
                    context.getLogger().log("Queued for deletion: " + obj.key()
                            + "  lastModified=" + obj.lastModified()
                            + "  size=" + obj.size() + " bytes");
                }
            }

            continuationToken = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (continuationToken != null);

        if (toDelete.isEmpty()) {
            String msg = "S3Cleanup: no files older than " + RETENTION_DAYS + " days found.";
            context.getLogger().log(msg);
            return msg;
        }

        // ── Batch delete (S3 allows up to 1000 per request) ───────────────────
        int deleted = 0;
        int batchSize = 1000;
        for (int i = 0; i < toDelete.size(); i += batchSize) {
            List<ObjectIdentifier> batch = toDelete.subList(i,
                    Math.min(i + batchSize, toDelete.size()));
            try {
                DeleteObjectsResponse resp = s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(INVOICE_BUCKET)
                        .delete(Delete.builder().objects(batch).quiet(false).build())
                        .build());

                deleted += resp.deleted().size();

                if (!resp.errors().isEmpty()) {
                    resp.errors().forEach(err ->
                            context.getLogger().log("Delete error: " + err.key()
                                    + " — " + err.message()));
                }
            } catch (Exception e) {
                context.getLogger().log("Batch delete failed: " + e.getMessage());
            }
        }

        String result = String.format("S3Cleanup: deleted %d file(s) older than %d days from s3://%s/%s",
                deleted, RETENTION_DAYS, INVOICE_BUCKET, INVOICE_PREFIX);
        context.getLogger().log(result);
        return result;
    }
}
