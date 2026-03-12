package io.ekbatan.core.persistence.jooq.converter;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.JSON;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class JSONObjectNodeConverterTest {

    private final JSONObjectNodeConverter converter = new JSONObjectNodeConverter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void from_converts_json_to_object_node() {
        // GIVEN
        var json = JSON.valueOf("{\"name\":\"test\",\"count\":42}");

        // WHEN
        var result = converter.from(json);

        // THEN
        assertThat(result).isNotNull();
        assertThatJson(result).isObject().containsEntry("name", "test").containsEntry("count", 42);
    }

    @Test
    void from_returns_null_for_null() {
        // GIVEN / WHEN / THEN
        assertThat(converter.from(null)).isNull();
    }

    @Test
    void from_returns_null_for_null_data() {
        // GIVEN / WHEN / THEN
        assertThat(converter.from(JSON.valueOf(null))).isNull();
    }

    @Test
    void to_converts_object_node_to_json() {
        // GIVEN
        var node = objectMapper.createObjectNode().put("key", "value");

        // WHEN
        var result = converter.to(node);

        // THEN
        assertThat(result).isNotNull();
        assertThatJson(result.data()).isObject().containsEntry("key", "value");
    }

    @Test
    void to_returns_null_for_null() {
        // GIVEN / WHEN / THEN
        assertThat(converter.to(null)).isNull();
    }

    @Test
    void round_trip_preserves_value() {
        // GIVEN
        var node = objectMapper.createObjectNode().put("a", 1).put("b", "two");

        // WHEN
        var result = converter.from(converter.to(node));

        // THEN
        assertThat(result).isEqualTo(node);
    }

    @Test
    void fromType_returns_json() {
        // GIVEN / WHEN / THEN
        assertThat(converter.fromType()).isEqualTo(JSON.class);
    }

    @Test
    void toType_returns_object_node() {
        // GIVEN / WHEN / THEN
        assertThat(converter.toType()).isEqualTo(ObjectNode.class);
    }
}
