package com.invoice.processing;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final String DYNAMO_TABLE = "invoices";

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
                // ── List all invoices (for dashboard / reviewer list view) ─
                return listAll(context);
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

    // ── Scan all items ─────────────────────────────────────────────────────────

    private Map<String, Object> listAll(Context ctx) throws Exception {
        List<Map<String, Object>> invoices = new ArrayList<>();

        ScanResponse scanResponse = dynamoDbClient.scan(
                ScanRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .build());

        scanResponse.items().forEach(item -> invoices.add(itemToMap(item)));

        ctx.getLogger().log("GetInvoice: listed " + invoices.size() + " invoices");
        return successResponse(invoices);
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
