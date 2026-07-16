package com.invoice.processing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * GetInvoiceHandler – called by API Gateway.
 *
 * Routes:
 *   GET /invoices          → list all invoices  (queryStringParameters absent)
 *   GET /invoices?id=XXX   → get single invoice by invoiceId
 *
 * API Gateway proxy integration passes the event as:
 * {
 *   "queryStringParameters": { "id": "# 11502" },   // optional
 *   "httpMethod": "GET"
 * }
 *
 * Response is always API Gateway proxy format:
 * {
 *   "statusCode": 200,
 *   "headers": { "Content-Type": "application/json",
 *                "Access-Control-Allow-Origin": "*" },
 *   "body": "<json string>"
 * }
 */
public class GetInvoiceHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String DYNAMO_TABLE = System.getenv("DYNAMO_TABLE") != null
            ? System.getenv("DYNAMO_TABLE") : "invoices";

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
            .region(Region.AP_SOUTH_1).build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            context.getLogger().log("GetInvoice EVENT: " + objectMapper.writeValueAsString(event));

            // Extract ?id=... query parameter if present
            String invoiceId = null;
            Object qsp = event.get("queryStringParameters");
            if (qsp instanceof Map) {
                invoiceId = (String) ((Map<?, ?>) qsp).get("id");
            }

            if (invoiceId != null && !invoiceId.isBlank()) {
                // ── Single invoice lookup ──────────────────────────────────
                return getById(invoiceId, context);
            } else {
                // ── Paginated list ─────────────────────────────────────────
                int pageSize = 20;
                String nextToken = null;

                if (qsp instanceof Map) {
                    String pageSizeStr = (String) ((Map<?, ?>) qsp).get("pageSize");
                    String nextTokenStr = (String) ((Map<?, ?>) qsp).get("nextToken");
                    if (pageSizeStr != null && !pageSizeStr.isBlank()) {
                        try { pageSize = Math.min(100, Math.max(1, Integer.parseInt(pageSizeStr))); }
                        catch (NumberFormatException ignored) {}
                    }
                    if (nextTokenStr != null && !nextTokenStr.isBlank()) {
                        nextToken = nextTokenStr;
                    }
                }

                return listPaged(pageSize, nextToken, context);
            }

        } catch (Exception e) {
            context.getLogger().log("GetInvoice ERROR: " + e.getMessage());
            return errorResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    // ── Single item ────────────────────────────────────────────────────────────

    private Map<String, Object> getById(String invoiceId, Context ctx) throws Exception {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("invoiceId", AttributeValue.builder().s(invoiceId).build());

        GetItemResponse response = dynamoDbClient.getItem(
                GetItemRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .key(key)
                        .build());

        if (!response.hasItem()) {
            return errorResponse(404, "Invoice not found: " + invoiceId);
        }

        Map<String, Object> invoice = itemToMap(response.item());
        ctx.getLogger().log("GetInvoice: found " + invoiceId);
        return successResponse(invoice);
    }

    // ── List invoices with cursor-based pagination ─────────────────────────────
    // Supports:
    //   GET /invoices                          → first page (default 20 items)
    //   GET /invoices?pageSize=50              → first page, 50 items
    //   GET /invoices?nextToken=<base64>       → next page using DynamoDB cursor
    //
    // Response:
    // {
    //   "items"    : [ ... ],
    //   "nextToken": "<base64>" or null   (null = last page)
    //   "pageSize" : 20,
    //   "count"    : 18
    // }

    private Map<String, Object> listPaged(int pageSize, String nextToken, Context ctx) throws Exception {

        ScanRequest.Builder builder = ScanRequest.builder()
                .tableName(DYNAMO_TABLE)
                .limit(pageSize);

        // Decode the nextToken (Base64 encoded JSON of the DynamoDB LastEvaluatedKey)
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                String decoded = new String(java.util.Base64.getUrlDecoder().decode(nextToken));
                Map<String, Object> keyMap = objectMapper.readValue(decoded, Map.class);
                Map<String, AttributeValue> startKey = new HashMap<>();
                keyMap.forEach((k, v) -> startKey.put(k, AttributeValue.builder().s(v.toString()).build()));
                builder.exclusiveStartKey(startKey);
            } catch (Exception e) {
                ctx.getLogger().log("WARNING: invalid nextToken, ignoring: " + e.getMessage());
            }
        }

        ScanResponse resp = dynamoDbClient.scan(builder.build());

        List<Map<String, Object>> items = new ArrayList<>();
        resp.items().forEach(item -> items.add(itemToMap(item)));

        // Encode the LastEvaluatedKey as a Base64 nextToken for the client
        String newNextToken = null;
        if (!resp.lastEvaluatedKey().isEmpty()) {
            Map<String, String> keyMap = new HashMap<>();
            resp.lastEvaluatedKey().forEach((k, v) -> keyMap.put(k, v.s()));
            String json = objectMapper.writeValueAsString(keyMap);
            newNextToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("items",             items);
        result.put("nextToken",         newNextToken);
        result.put("pageSize",          pageSize);
        result.put("count",             items.size());
        result.put("totalCount",        getTotalCount(ctx));
        result.put("totalApproved",     getCountByStatus("APPROVED", ctx));
        result.put("totalReview",       getCountByStatus("REVIEW_REQUIRED", ctx));
        result.put("totalDuplicate",    getCountByStatus("DUPLICATE", ctx));
        result.put("totalHumanApproved", getCountByDecision("APPROVED", ctx));
        result.put("totalHumanRejected", getCountByDecision("REJECTED", ctx));

        ctx.getLogger().log("GetInvoice: returned " + items.size()
                + " items, hasMore=" + (newNextToken != null));

        return successResponse(result);
    }

    // ── Get count of items by validationStatus ────────────────────────────────
    private int getCountByStatus(String status, Context ctx) {
        try {
            int count = 0;
            Map<String, AttributeValue> lastKey = null;
            do {
                ScanRequest.Builder b = ScanRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .filterExpression("validationStatus = :s")
                        .expressionAttributeValues(Map.of(
                                ":s", AttributeValue.builder().s(status).build()))
                        .select("COUNT");
                if (lastKey != null) b.exclusiveStartKey(lastKey);
                ScanResponse r = dynamoDbClient.scan(b.build());
                count  += r.count();
                lastKey = r.lastEvaluatedKey().isEmpty() ? null : r.lastEvaluatedKey();
            } while (lastKey != null);
            return count;
        } catch (Exception e) {
            ctx.getLogger().log("WARNING: getCountByStatus(" + status + ") failed: " + e.getMessage());
            return 0;
        }
    }

    // ── Get count of items by reviewDecision ──────────────────────────────────
    private int getCountByDecision(String decision, Context ctx) {
        try {
            int count = 0;
            Map<String, AttributeValue> lastKey = null;
            do {
                ScanRequest.Builder b = ScanRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .filterExpression("reviewDecision = :d")
                        .expressionAttributeValues(Map.of(
                                ":d", AttributeValue.builder().s(decision).build()))
                        .select("COUNT");
                if (lastKey != null) b.exclusiveStartKey(lastKey);
                ScanResponse r = dynamoDbClient.scan(b.build());
                count  += r.count();
                lastKey = r.lastEvaluatedKey().isEmpty() ? null : r.lastEvaluatedKey();
            } while (lastKey != null);
            return count;
        } catch (Exception e) {
            ctx.getLogger().log("WARNING: getCountByDecision(" + decision + ") failed: " + e.getMessage());
            return 0;
        }
    }

    // ── Get total item count via DynamoDB table scan count ────────────────────
    private int getTotalCount(Context ctx) {
        try {
            software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest req =
                    software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest.builder()
                            .tableName(DYNAMO_TABLE)
                            .build();
            return (int) dynamoDbClient.describeTable(req).table().itemCount().intValue();
        } catch (Exception e) {
            ctx.getLogger().log("WARNING: could not get totalCount: " + e.getMessage());
            return -1;
        }
    }

    // ── DynamoDB item → plain Map ──────────────────────────────────────────────

    private Map<String, Object> itemToMap(Map<String, AttributeValue> item) {
        Map<String, Object> map = new HashMap<>();
        item.forEach((key, attr) -> {
            if (attr.s()  != null) map.put(key, attr.s());
            else if (attr.n() != null) map.put(key, Double.parseDouble(attr.n()));
            else if (attr.bool() != null) map.put(key, attr.bool());
            else map.put(key, attr.toString());
        });
        return map;
    }

    // ── API Gateway response helpers ───────────────────────────────────────────

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
                "Content-Type", "application/json",
                "Access-Control-Allow-Origin", "*",        // required for Amplify / browser calls
                "Access-Control-Allow-Methods", "GET,POST,OPTIONS",
                "Access-Control-Allow-Headers", "Content-Type,Authorization"
        ));
        response.put("body", jsonBody);
        return response;
    }
}
