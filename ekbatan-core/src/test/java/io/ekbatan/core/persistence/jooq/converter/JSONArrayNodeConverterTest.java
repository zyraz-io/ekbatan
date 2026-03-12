package io.ekbatan.core.persistence.jooq.converter;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.JSON;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

class JSONArrayNodeConverterTest {

    private final JSONArrayNodeConverter converter = new JSONArrayNodeConverter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void from_converts_json_to_array_node() {
        // GIVEN
        var json = JSON.valueOf("[\"a\",\"b\",\"c\"]");

        // WHEN
        var result = converter.from(json);

        // THEN
        assertThat(result).isNotNull();
        assertThatJson(result).isArray().containsExactly("a", "b", "c");
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
    void to_converts_array_node_to_json() {
        // GIVEN
        var node = objectMapper.createArrayNode().add(1).add(2).add(3);

        // WHEN
        var result = converter.to(node);

        // THEN
        assertThat(result).isNotNull();
        assertThatJson(result.data()).isArray().containsExactly(1, 2, 3);
    }

    @Test
    void to_returns_null_for_null() {
        // GIVEN / WHEN / THEN
        assertThat(converter.to(null)).isNull();
    }

    @Test
    void round_trip_preserves_value() {
        // GIVEN
        var node = objectMapper.createArrayNode().add("x").add("y");

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
    void toType_returns_array_node() {
        // GIVEN / WHEN / THEN
        assertThat(converter.toType()).isEqualTo(ArrayNode.class);
    }
}
