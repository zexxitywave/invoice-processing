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
 * Secret value (JSON):
 * {
 *   "sesSender"   : "noreply@zexxity.online",
 *   "sesReviewer" : "invydexter@gmail.com",
 *   "modelId"     : "apac.amazon.nova-lite-v1:0",
 *   "frontendUrl" : "https://www.zexxity.online"
 * }
 *
 * Cached at class-load time so multiple Lambda invocations in the same
 * execution environment pay the Secrets Manager API cost only once.
 */
public class SecretsManagerConfig {

    public static final String SECRET_NAME = "invoice-processing/config";

    // ── Real defaults – used when Secrets Manager AND env-vars are both absent ─
    private static final String DEFAULT_SENDER      = "noreply@zexxity.online";
    private static final String DEFAULT_REVIEWER    = "invydexter@gmail.com";
    private static final String DEFAULT_MODEL_ID    = "apac.amazon.nova-lite-v1:0";
    private static final String DEFAULT_FRONTEND_URL = "https://zexxity.online";

    // ── Cached values ──────────────────────────────────────────────────────────
    private final String sesSender;
    private final String sesReviewer;
    private final String modelId;
    private final String frontendUrl;

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

    // ── Constructor ────────────────────────────────────────────────────────────
    private SecretsManagerConfig() {
        String sender      = null;
        String reviewer    = null;
        String model       = null;
        String url         = null;

        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.AP_SOUTH_1)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(SECRET_NAME)
                            .build());

            JsonNode root = new ObjectMapper().readTree(response.secretString());

            sender   = textOrNull(root, "sesSender");
            reviewer = textOrNull(root, "sesReviewer");
            model    = textOrNull(root, "modelId");
            url      = textOrNull(root, "frontendUrl");

            System.out.println("SecretsManagerConfig: loaded from Secrets Manager.");

        } catch (Exception e) {
            System.err.println("SecretsManagerConfig: could not load secret '"
                    + SECRET_NAME + "': " + e.getMessage()
                    + " – falling back to env-vars / defaults.");
        }

        // Priority: Secrets Manager → env-var → hardcoded real default
        this.sesSender    = firstNonBlank(sender,   System.getenv("SES_SENDER"),    DEFAULT_SENDER);
        this.sesReviewer  = firstNonBlank(reviewer, System.getenv("SES_REVIEWER"),  DEFAULT_REVIEWER);
        this.modelId      = firstNonBlank(model,    System.getenv("MODEL_ID"),      DEFAULT_MODEL_ID);
        this.frontendUrl  = firstNonBlank(url,      System.getenv("FRONTEND_URL"),  DEFAULT_FRONTEND_URL);

        System.out.println("SecretsManagerConfig: sesSender="   + this.sesSender);
        System.out.println("SecretsManagerConfig: sesReviewer=" + this.sesReviewer);
        System.out.println("SecretsManagerConfig: frontendUrl=" + this.frontendUrl);
    }

    // ── Accessors ──────────────────────────────────────────────────────────────
    public String getSesSender()    { return sesSender;    }
    public String getSesReviewer()  { return sesReviewer;  }
    public String getModelId()      { return modelId;      }
    public String getFrontendUrl()  { return frontendUrl;  }

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
