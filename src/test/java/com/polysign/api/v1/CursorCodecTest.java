package com.polysign.api.v1;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    @Test
    void roundtrip_stringKey() {
        Map<String, AttributeValue> key = Map.of(
                "alertId",   AttributeValue.fromS("abc123"),
                "createdAt", AttributeValue.fromS("2026-04-11T00:00:00Z"));

        String cursor = CursorCodec.encode(key);
        assertThat(cursor).isNotBlank();
        assertThat(cursor).doesNotContain("="); // no padding

        Map<String, AttributeValue> decoded = CursorCodec.decode(cursor);
        assertThat(decoded.get("alertId").s()).isEqualTo("abc123");
        assertThat(decoded.get("createdAt").s()).isEqualTo("2026-04-11T00:00:00Z");
    }

    @Test
    void roundtrip_numberKey() {
        Map<String, AttributeValue> key = Map.of(
                "marketId",  AttributeValue.fromS("market-1"),
                "timestamp", AttributeValue.fromS("2026-04-11T00:00:00Z"));

        String cursor = CursorCodec.encode(key);
        Map<String, AttributeValue> decoded = CursorCodec.decode(cursor);

        assertThat(decoded.get("marketId").s()).isEqualTo("market-1");
        assertThat(decoded.get("timestamp").s()).isEqualTo("2026-04-11T00:00:00Z");
    }

    @Test
    void decode_invalidBase64_throwsInvalidCursorException() {
        assertThatThrownBy(() -> CursorCodec.decode("!!!not-base64!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void decode_validBase64ButInvalidJson_throwsInvalidCursorException() {
        String garbage = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-json".getBytes());
        assertThatThrownBy(() -> CursorCodec.decode(garbage))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void decode_validJsonButUnknownAttributeType_throwsInvalidCursorException() {
        // A JSON with a BOOL type-tagged value — not supported
        String json = "{\"pk\":{\"BOOL\":\"true\"}}";
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CursorCodec.decode(cursor))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void encode_producesNoBase64Padding() {
        Map<String, AttributeValue> key = Map.of("id", AttributeValue.fromS("x"));
        String cursor = CursorCodec.encode(key);
        assertThat(cursor).doesNotContain("=");
    }
}
