package io.ekbatan.events.streaming.debeziumsmt.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The mapping key is the schema's own fully-qualified name, and every part of it is checked at
 * startup.
 *
 * <p>The alternative - validating lazily, or not at all - turns every mistake here into "no schema
 * configured for X" on the first record, which is a long way from the typo that caused it.
 */
class PayloadSchemaBindingsTest {

    private static final String AVRO = PayloadSchemaBindings.AVRO;
    private static final String PROTO = PayloadSchemaBindings.PROTOBUF;

    @Test
    void the_name_a_row_expects_is_namespace_then_format_then_event_type() {
        assertThat(PayloadSchemaBindings.qualifiedName("com.shop.billing", AVRO, "OrderCreated"))
                .isEqualTo("com.shop.billing.avro.OrderCreated");
        assertThat(PayloadSchemaBindings.qualifiedName("com.shop.billing", PROTO, "OrderCreated"))
                .isEqualTo("com.shop.billing.proto.OrderCreated");
    }

    @Test
    void entries_are_parsed_in_declaration_order() {
        var bindings = PayloadSchemaBindings.parse(
                "com.shop.billing.avro.OrderCreated:/a.avsc, com.shop.warehouse.avro.OrderCreated:/b.avsc",
                AVRO,
                "payload.schemas");

        assertThat(bindings)
                .containsExactly(
                        org.assertj.core.api.Assertions.entry("com.shop.billing.avro.OrderCreated", "/a.avsc"),
                        org.assertj.core.api.Assertions.entry("com.shop.warehouse.avro.OrderCreated", "/b.avsc"));
    }

    /**
     * The case the namespace exists for. Two services sharing one outbox table can each define
     * {@code OrderCreated}; keyed on the event type alone, one would have been encoded with the
     * other's schema.
     */
    @Test
    void two_namespaces_may_share_an_event_name() {
        var bindings = PayloadSchemaBindings.parse(
                "com.shop.billing.avro.OrderCreated:/billing.avsc,com.shop.warehouse.avro.OrderCreated:/wh.avsc",
                AVRO,
                "payload.schemas");

        assertThat(bindings).hasSize(2);
        assertThat(bindings.get(PayloadSchemaBindings.qualifiedName("com.shop.billing", AVRO, "OrderCreated")))
                .isEqualTo("/billing.avsc");
        assertThat(bindings.get(PayloadSchemaBindings.qualifiedName("com.shop.warehouse", AVRO, "OrderCreated")))
                .isEqualTo("/wh.avsc");
    }

    /**
     * A path may itself contain a colon, so the split has to be at the first one. Keys are
     * dot-separated identifiers and can never contain a colon, which is what makes that safe.
     */
    @Test
    void a_path_containing_a_colon_survives() {
        var bindings = PayloadSchemaBindings.parse("a.avro.E:/opt/weird:dir/E.avsc", AVRO, "payload.schemas");

        assertThat(bindings).containsValue("/opt/weird:dir/E.avsc");
    }

    /** Pasting a protobuf key into the Avro transform must fail here, not silently match no record. */
    @Test
    void the_format_segment_must_match_the_transform() {
        assertThatThrownBy(() -> PayloadSchemaBindings.parse("a.b.proto.E:/x.avsc", AVRO, "payload.schemas"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proto")
                .hasMessageContaining("avro");
    }

    /** There is no bare-event-type form: a wildcard would quietly match namespaces it never meant to. */
    @ParameterizedTest
    @ValueSource(strings = {"OrderCreated:/x.avsc", "billing.OrderCreated:/x.avsc"})
    void a_key_without_a_namespace_and_format_is_rejected(String spec) {
        assertThatThrownBy(() -> PayloadSchemaBindings.parse(spec, AVRO, "payload.schemas"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<namespace>");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "my-shop.avro.E:/x.avsc",
                "shop..avro.E:/x.avsc",
                "9shop.avro.E:/x.avsc",
                "shop.avro.E F:/x.avsc",
            })
    void a_key_that_is_not_dot_separated_identifiers_is_rejected(String spec) {
        assertThatThrownBy(() -> PayloadSchemaBindings.parse(spec, AVRO, "payload.schemas"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identifier");
    }

    @Test
    void an_entry_with_no_path_is_rejected() {
        assertThatThrownBy(() -> PayloadSchemaBindings.parse("a.avro.E:", AVRO, "payload.schemas"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty path");
        assertThatThrownBy(() -> PayloadSchemaBindings.parse("a.avro.E", AVRO, "payload.schemas"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Two paths for one name is a copy-paste error, not an override. */
    @Test
    void the_same_name_mapped_to_two_paths_is_rejected() {
        assertThatThrownBy(() -> PayloadSchemaBindings.parse("a.avro.E:/one.avsc,a.avro.E:/two.avsc", AVRO, "cfg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/one.avsc")
                .hasMessageContaining("/two.avsc");
    }

    /** Repeating the identical entry is harmless - it says the same thing twice. */
    @Test
    void the_same_name_mapped_to_the_same_path_twice_is_accepted() {
        assertThat(PayloadSchemaBindings.parse("a.avro.E:/one.avsc,a.avro.E:/one.avsc", AVRO, "cfg"))
                .hasSize(1);
    }

    @Test
    void an_empty_mapping_is_rejected_rather_than_yielding_a_transform_that_encodes_nothing() {
        assertThatThrownBy(() -> PayloadSchemaBindings.parse("   ", AVRO, "payload.schemas"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    /** Trailing commas and padding are ordinary in a long multi-line connector config. */
    @Test
    void blank_entries_and_padding_are_tolerated() {
        assertThat(PayloadSchemaBindings.parse("  a.avro.E:/x.avsc ,, b.avro.F:/y.avsc ,", AVRO, "cfg"))
                .hasSize(2);
    }
}
