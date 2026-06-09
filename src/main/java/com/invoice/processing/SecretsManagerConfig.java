package com.invoice.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/**
 * Reads invoice-processing config from AWS Secrets Manager.
 *
 * Secret name  : invoice-processing/config
 * Secret value : {
 *   "sesSender"   : "invoice@zexxity.online",
 *   "sesReviewer" : "invydexter@gmail.com",
 *   "modelId"     : "apac.amazon.nova-lite-v1:0"
 * }
 *
 * Cached at class-load time so multiple Lambda invocations in the same
 * execution environment pay the Secrets Manager API cost only once.
 */
public class SecretsManagerConfig {

    public static final String SECRET_NAME = "invoice-processing/config";

    // ── Cached values ──────────────────────────────────────────────────────────
    private final String sesSender;
    private final String sesReviewer;
    private final String modelId;

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static volatile SecretsManagerConfig INSTANCE;

    public static SecretsManagerConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (SecretsManagerConfig.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SecretsManagerConfig();
                }
            }
        }
        return INSTANCE;
    }

    // ── Constructor – loads from Secrets Manager with env-var fallback ─────────
    private SecretsManagerConfig() {
        String sender   = null;
        String reviewer = null;
        String model    = null;

        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.AP_SOUTH_1)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(SECRET_NAME)
                            .build());

            String secretJson = response.secretString();
            JsonNode root = new ObjectMapper().readTree(secretJson);

            sender   = textOrNull(root, "sesSender");
            reviewer = textOrNull(root, "sesReviewer");
            model    = textOrNull(root, "modelId");

        } catch (Exception e) {
            // Secrets Manager not reachable or secret doesn't exist yet –
            // fall through to env-var / hardcoded defaults so the Lambda
            // still boots during local testing or first deploy.
            System.err.println("SecretsManagerConfig: could not load secret '"
                    + SECRET_NAME + "': " + e.getMessage()
                    + " – falling back to environment variables.");
        }

        // Env-var fallbacks (set these in the Lambda console if not using Secrets Manager)
        this.sesSender   = firstNonBlank(sender,   System.getenv("SES_SENDER"),   "sender@example.com");
        this.sesReviewer = firstNonBlank(reviewer, System.getenv("SES_REVIEWER"), "reviewer@example.com");
        this.modelId     = firstNonBlank(model,    System.getenv("MODEL_ID"),     "apac.amazon.nova-lite-v1:0");
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public String getSesSender()   { return sesSender;   }
    public String getSesReviewer() { return sesReviewer; }
    public String getModelId()     { return modelId;     }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull() && !n.asText().isBlank()) ? n.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
