package com.polysign.api.v1;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes and decodes DynamoDB {@code LastEvaluatedKey} maps as opaque base64url cursor strings.
 *
 * <p>Wire format: base64url(JSON) where JSON is a map of attribute name → type-tagged value:
 * <pre>
 *   {"alertId": {"S": "abc123"}, "createdAt": {"S": "2026-04-11T00:00:00Z"}}
 * </pre>
 *
 * <p>Only S (String) and N (Number) types are supported — sufficient for all PolySign tables.
 * Any other type throws {@link InvalidCursorException} on decode.
 *
 * <p>Malformed base64 or JSON throws {@link InvalidCursorException}; callers should
 * propagate it to return HTTP 400.
 */
public final class CursorCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Map<String, String>>> TYPE_REF =
            new TypeReference<>() {};

    private CursorCodec() {}

    /**
     * Encodes a DynamoDB LastEvaluatedKey into a base64url cursor string.
     *
     * @param lastEvaluatedKey non-null, non-empty map from DynamoDB page response
     * @return opaque cursor token (no padding)
     */
    public static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        Map<String, Map<String, String>> wire = new LinkedHashMap<>();
        for (Map.Entry<String, AttributeValue> entry : lastEvaluatedKey.entrySet()) {
            AttributeValue av = entry.getValue();
            Map<String, String> typed = new LinkedHashMap<>();
            if (av.s() != null) {
                typed.put("S", av.s());
            } else if (av.n() != null) {
                typed.put("N", av.n());
            }
            wire.put(entry.getKey(), typed);
        }
        try {
            byte[] json = MAPPER.writeValueAsBytes(wire);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    /**
     * Decodes a cursor string back to a DynamoDB exclusiveStartKey map.
     *
     * @param cursor opaque cursor token produced by {@link #encode}
     * @return DynamoDB key map suitable for {@code exclusiveStartKey}
     * @throws InvalidCursorException if the cursor is malformed
     */
    public static Map<String, AttributeValue> decode(String cursor) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String json = new String(decoded, StandardCharsets.UTF_8);
            Map<String, Map<String, String>> wire = MAPPER.readValue(json, TYPE_REF);
            Map<String, AttributeValue> result = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : wire.entrySet()) {
                Map<String, String> typed = entry.getValue();
                AttributeValue av;
                if (typed.containsKey("S")) {
                    av = AttributeValue.fromS(typed.get("S"));
                } else if (typed.containsKey("N")) {
                    av = AttributeValue.fromN(typed.get("N"));
                } else {
                    throw new InvalidCursorException("Invalid cursor: unknown attribute type");
                }
                result.put(entry.getKey(), av);
            }
            return result;
        } catch (InvalidCursorException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCursorException("Invalid cursor: " + e.getMessage());
        }
    }
}
