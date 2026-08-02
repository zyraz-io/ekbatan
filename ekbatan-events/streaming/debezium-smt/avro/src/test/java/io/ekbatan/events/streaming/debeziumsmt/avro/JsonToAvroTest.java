package io.ekbatan.events.streaming.debeziumsmt.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.connect.errors.DataException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The payload conversion, which the old implementation got wrong in three separate ways: lenient
 * accessors that returned defaults instead of failing, union resolution that took the first branch
 * that did not throw (and nothing ever threw), and logical types that were never looked at.
 *
 * <p>None of it was caught before because the only payload schema under test had a single string
 * field - the one shape that happens to work by accident.
 */
class JsonToAvroTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> warnings = new ArrayList<>();
    private final JsonToAvro converter = new JsonToAvro(warnings::add);

    @Nested
    class LeavesThatCanFail {

        /** The headline regression: text into a long used to become 0 and reach Kafka. */
        @Test
        void text_in_a_numeric_field_fails_instead_of_becoming_zero() {
            var schema = record("{\"name\":\"count\",\"type\":\"long\"}");

            assertThatThrownBy(() -> convert("{\"count\":\"ORDER-123\"}", schema))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining("count")
                    .hasMessageContaining("needs a number");
        }

        @Test
        void a_number_too_large_for_an_int_fails_rather_than_wrapping() {
            var schema = record("{\"name\":\"n\",\"type\":\"int\"}");

            assertThatThrownBy(() -> convert("{\"n\":99999999999}", schema))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining("fits in an int");
        }

        @Test
        void a_number_in_a_string_field_fails() {
            var schema = record("{\"name\":\"s\",\"type\":\"string\"}");

            assertThatThrownBy(() -> convert("{\"s\":42}", schema))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining("needs a string");
        }

        @Test
        void well_typed_values_convert() {
            var schema = record(
                    "{\"name\":\"s\",\"type\":\"string\"}",
                    "{\"name\":\"n\",\"type\":\"long\"}",
                    "{\"name\":\"b\",\"type\":\"boolean\"}",
                    "{\"name\":\"d\",\"type\":\"double\"}");

            var out = convert("{\"s\":\"x\",\"n\":7,\"b\":true,\"d\":1.5}", schema);

            assertThat(out.get("s")).isEqualTo("x");
            assertThat(out.get("n")).isEqualTo(7L);
            assertThat(out.get("b")).isEqualTo(true);
            assertThat(out.get("d")).isEqualTo(1.5d);
        }

        @Test
        void a_field_missing_with_no_default_names_itself() {
            var schema = record("{\"name\":\"count\",\"type\":\"long\"}");

            assertThatThrownBy(() -> convert("{}", schema))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining("Missing field")
                    .hasMessageContaining("count");
        }

        @Test
        void a_field_missing_with_a_default_takes_it() {
            var schema = record("{\"name\":\"count\",\"type\":\"long\",\"default\":5}");

            assertThat(convert("{}", schema).get("count")).isEqualTo(5L);
        }
    }

    @Nested
    class LogicalTypes {

        /** #14: decimal used to crash, because a JSON number is not base64 bytes. */
        @Test
        void a_decimal_encodes_from_a_json_number() {
            var schema = record(
                    "{\"name\":\"amount\",\"type\":{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":10,\"scale\":2}}");

            var bytes = (ByteBuffer) convert("{\"amount\":77.10}", schema).get("amount");

            assertThat(new BigDecimal(new java.math.BigInteger(bytes.array()), 2))
                    .isEqualByComparingTo(new BigDecimal("77.10"));
        }

        /** Losing money quietly is exactly what this class exists to prevent. */
        @Test
        void a_decimal_too_precise_for_the_scale_fails_rather_than_rounding() {
            var schema = record(
                    "{\"name\":\"amount\",\"type\":{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":10,\"scale\":2}}");

            assertThatThrownBy(() -> convert("{\"amount\":77.109}", schema))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining("without rounding");
        }

        @Test
        void a_timestamp_accepts_both_a_number_and_an_iso_string() {
            var schema = record("{\"name\":\"at\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}}");
            var when = Instant.parse("2026-08-02T10:15:30.123Z");

            assertThat(convert("{\"at\":" + when.toEpochMilli() + "}", schema).get("at"))
                    .isEqualTo(when.toEpochMilli());
            assertThat(convert("{\"at\":\"" + when + "\"}", schema).get("at")).isEqualTo(when.toEpochMilli());
        }

        @Test
        void timestamp_micros_is_scaled_not_truncated() {
            var schema = record("{\"name\":\"at\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}}");
            var when = Instant.parse("2026-08-02T10:15:30.123456Z");

            assertThat(convert("{\"at\":\"" + when + "\"}", schema).get("at"))
                    .isEqualTo(when.getEpochSecond() * 1_000_000L + when.getNano() / 1_000L);
        }

        @Test
        void a_date_accepts_an_iso_string() {
            var schema = record("{\"name\":\"d\",\"type\":{\"type\":\"int\",\"logicalType\":\"date\"}}");

            assertThat(convert("{\"d\":\"2026-08-02\"}", schema).get("d"))
                    .isEqualTo((int) LocalDate.parse("2026-08-02").toEpochDay());
        }

        @Test
        void an_unparseable_timestamp_names_the_field_and_the_value() {
            var schema = record("{\"name\":\"at\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}}");

            assertThatThrownBy(() -> convert("{\"at\":\"yesterday\"}", schema))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining("at")
                    .hasMessageContaining("yesterday");
        }
    }

    @Nested
    class Unions {

        /** ["null", T] is just how Avro spells "optional" - the schema decides it outright. */
        @Test
        void an_optional_field_is_decided_by_the_schema_alone() {
            var schema = record("{\"name\":\"note\",\"type\":[\"null\",\"string\"],\"default\":null}");

            assertThat(convert("{\"note\":\"hi\"}", schema).get("note")).isEqualTo("hi");
            assertThat(convert("{\"note\":null}", schema).get("note")).isNull();
        }

        /**
         * The corruption case. Declared long-or-string, holding text: the old code tried `long`,
         * got 0 from asLong(), never threw, and wrote 0 - discarding the value entirely.
         */
        @Test
        void text_in_a_long_or_string_union_picks_string_not_zero() {
            var schema = record("{\"name\":\"ref\",\"type\":[\"null\",\"long\",\"string\"],\"default\":null}");

            assertThat(convert("{\"ref\":\"ORDER-123\"}", schema).get("ref")).isEqualTo("ORDER-123");
        }

        @Test
        void a_number_in_the_same_union_still_picks_long() {
            var schema = record("{\"name\":\"ref\",\"type\":[\"null\",\"long\",\"string\"],\"default\":null}");

            assertThat(convert("{\"ref\":42}", schema).get("ref")).isEqualTo(42L);
        }

        /** Best fit: the branch that accounts for the most of the JSON's keys. */
        @Test
        void a_union_of_records_picks_the_branch_that_explains_the_payload() {
            var card = "{\"type\":\"record\",\"name\":\"Card\",\"fields\":["
                    + "{\"name\":\"kind\",\"type\":\"string\"},{\"name\":\"last4\",\"type\":\"string\"}]}";
            var bank = "{\"type\":\"record\",\"name\":\"Bank\",\"fields\":["
                    + "{\"name\":\"kind\",\"type\":\"string\"},"
                    + "{\"name\":\"iban\",\"type\":[\"null\",\"string\"],\"default\":null}]}";
            var schema = record("{\"name\":\"method\",\"type\":[\"null\"," + card + "," + bank + "],\"default\":null}");

            var out = (GenericRecord) convert("{\"method\":{\"kind\":\"card\",\"last4\":\"1234\"}}", schema)
                    .get("method");

            assertThat(out.getSchema().getName()).isEqualTo("Card");
            assertThat(out.get("last4")).isEqualTo("1234");
        }

        @Test
        void a_value_matching_no_branch_names_the_field_and_what_was_tried() {
            var schema = record("{\"name\":\"n\",\"type\":[\"null\",\"long\",\"boolean\"],\"default\":null}");

            assertThatThrownBy(() -> convert("{\"n\":\"neither\"}", schema))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining("n")
                    .hasMessageContaining("matches no branch");
        }
    }

    @Nested
    class Structure {

        @Test
        void nested_records_and_arrays_convert_all_the_way_down() {
            var inner = "{\"type\":\"record\",\"name\":\"Line\",\"fields\":["
                    + "{\"name\":\"sku\",\"type\":\"string\"},{\"name\":\"qty\",\"type\":\"int\"}]}";
            var schema = record("{\"name\":\"lines\",\"type\":{\"type\":\"array\",\"items\":" + inner + "}}");

            var out = convert("{\"lines\":[{\"sku\":\"A\",\"qty\":2},{\"sku\":\"B\",\"qty\":3}]}", schema);

            @SuppressWarnings("unchecked")
            var lines = (List<GenericRecord>) out.get("lines");
            assertThat(lines).hasSize(2);
            assertThat(lines.get(1).get("sku")).isEqualTo("B");
            assertThat(lines.get(1).get("qty")).isEqualTo(3);
        }

        /** A bad value deep inside must still say exactly where it was. */
        @Test
        void a_failure_deep_in_the_tree_reports_its_path() {
            var inner = "{\"type\":\"record\",\"name\":\"Line\",\"fields\":[{\"name\":\"qty\",\"type\":\"int\"}]}";
            var schema = record("{\"name\":\"lines\",\"type\":{\"type\":\"array\",\"items\":" + inner + "}}");

            assertThatThrownBy(() -> convert("{\"lines\":[{\"qty\":1},{\"qty\":\"two\"}]}", schema))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining("lines[1].qty");
        }
    }

    @Nested
    class Drift {

        /**
         * A field added to the event class but not to the schema is dropped - tolerable, so an
         * additive change cannot take the pipeline down - but never silently.
         */
        @Test
        void an_undeclared_payload_field_is_dropped_with_a_warning() {
            var schema = record("{\"name\":\"amount\",\"type\":\"string\"}");

            var out = convert("{\"amount\":\"77.10\",\"currency\":\"EUR\"}", schema);

            assertThat(out.get("amount")).isEqualTo("77.10");
            assertThat(warnings).hasSize(1);
            assertThat(warnings.getFirst()).contains("currency").contains("drifted apart");
        }

        @Test
        void a_matching_payload_warns_about_nothing() {
            var schema = record("{\"name\":\"amount\",\"type\":\"string\"}");

            convert("{\"amount\":\"77.10\"}", schema);

            assertThat(warnings).isEmpty();
        }
    }

    private GenericRecord convert(String json, Schema schema) {
        return converter.convert(parse(json), schema, "TestEvent");
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Schema record(String... fields) {
        return new Schema.Parser()
                .parse("{\"type\":\"record\",\"name\":\"TestEvent\",\"fields\":[" + String.join(",", fields) + "]}");
    }
}
