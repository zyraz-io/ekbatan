package io.ekbatan.events.streaming.debeziumsmt.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses the {@code payload.schemas} / {@code payload.descriptors} mapping and derives the schema
 * name an outbox row expects.
 *
 * <h2>The key is the schema's own fully-qualified name</h2>
 *
 * <pre>
 * payload.schemas=com.shop.billing.avro.OrderCreated:/schemas/billing_order.avsc
 *                \____ namespace ____/ \fmt/ \ event_type /
 * </pre>
 *
 * <p>All three parts are known at runtime - {@code namespace} and {@code event_type} are columns on
 * the row, and the format segment is fixed per transform - so the same string can be rebuilt from a
 * record and looked up. Writing it out in full rather than deriving it from a shorter key is
 * deliberate: the configured key, the name searched for in the schema, and the name quoted in any
 * error are then character-for-character identical, so a failure can be diffed against the config
 * by eye.
 *
 * <h2>Why the namespace is part of the key</h2>
 *
 * <p>Selection used to be by {@code event_type} alone, which cannot express the case the
 * {@code namespace} column exists for: two services sharing one {@code eventlog.events} table.
 * Per-service uniqueness of event names is enforced on a per-persister instance, so two deployments
 * may each define {@code OrderCreated} with different shapes - and one of them would have been
 * encoded with the other's schema, silently, whenever the two happened to be field-compatible.
 *
 * <p>There is no bare {@code EventType} wildcard form. A wildcard would quietly match namespaces it
 * was never meant to, which is the same failure wearing a different hat.
 */
public final class PayloadSchemaBindings {

    /** The segment identifying the wire format, sitting between the namespace and the event type. */
    public static final String AVRO = "avro";

    /** The segment identifying the wire format, sitting between the namespace and the event type. */
    public static final String PROTOBUF = "proto";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private PayloadSchemaBindings() {}

    /**
     * A {@link org.apache.kafka.common.config.ConfigDef.Validator} for the mapping property, so a
     * malformed value is reported when the connector configuration is <em>submitted</em> rather
     * than when its task starts.
     *
     * <p>Without one, Kafka Connect has no rule to check against: it accepts the configuration,
     * creates the connector, starts the task, and only then does {@code configure()} throw. The
     * operator is left with a connector that exists and does not work, and has to read the task
     * logs to find out why - for a mistake that was visible in the text they submitted.
     *
     * <p>Syntax only. Whether the files exist and declare the right names is checked in
     * {@code configure()}, because that is I/O against paths that may legitimately not be mounted
     * yet at the moment the configuration is validated.
     *
     * @param format {@link #AVRO} or {@link #PROTOBUF}, the segment keys must carry
     * @return a validator that rejects a mapping this transform could not parse
     */
    public static org.apache.kafka.common.config.ConfigDef.Validator validator(String format) {
        return new org.apache.kafka.common.config.ConfigDef.Validator() {
            @Override
            public void ensureValid(String name, Object value) {
                if (value == null) {
                    return; // absence is ConfigDef's own business, not this rule's
                }
                try {
                    parse((String) value, format, name);
                } catch (IllegalArgumentException e) {
                    throw new org.apache.kafka.common.config.ConfigException(name, value, e.getMessage());
                }
            }

            @Override
            public String toString() {
                return "comma-separated <namespace>." + format + ".<EventType>:<path> pairs";
            }
        };
    }

    /**
     * The schema name a row expects: {@code namespace.format.EventType}.
     *
     * @param namespace the row's {@code namespace} column
     * @param format {@link #AVRO} or {@link #PROTOBUF}
     * @param eventType the row's {@code event_type} column
     * @return the fully-qualified schema name to look up
     */
    public static String qualifiedName(String namespace, String format, String eventType) {
        return namespace + "." + format + "." + eventType;
    }

    /**
     * Parses {@code name:path,name:path} into an ordered map of qualified name to path.
     *
     * <p>Every key is validated here rather than at first use, so a malformed or misplaced entry
     * stops the connector at startup instead of silently never matching a row.
     *
     * @param spec the raw config value
     * @param format the segment this transform requires, {@link #AVRO} or {@link #PROTOBUF}
     * @param configName the property name, for error messages
     * @return qualified schema name to file path, in declaration order
     */
    public static Map<String, String> parse(String spec, String format, String configName) {
        final var bindings = new LinkedHashMap<String, String>();
        for (final var entry : spec.split(",")) {
            final var trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // The key is dot-separated identifiers and the path may itself contain a colon, so the
            // split is at the first colon rather than the last.
            final var separator = trimmed.indexOf(':');
            if (separator < 0) {
                throw new IllegalArgumentException(configName + " entry '" + trimmed
                        + "' is not of the form <namespace>." + format + ".<EventType>:<path>");
            }
            final var name = trimmed.substring(0, separator).trim();
            final var path = trimmed.substring(separator + 1).trim();
            requireQualifiedName(name, format, configName);
            if (path.isEmpty()) {
                throw new IllegalArgumentException(configName + " entry '" + name + "' has an empty path");
            }
            final var previous = bindings.put(name, path);
            if (previous != null && !previous.equals(path)) {
                throw new IllegalArgumentException(
                        configName + " maps '" + name + "' to two different paths: " + previous + " and " + path);
            }
        }
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException(configName + " is empty; at least one <namespace>." + format
                    + ".<EventType>:<path> mapping is required");
        }
        return bindings;
    }

    /**
     * Fails unless {@code name} is {@code <namespace>.<format>.<EventType>}.
     *
     * <p>The format segment is checked explicitly. Without that, pasting a {@code .proto.} key into
     * the Avro transform would parse cleanly and then match no record at all, surfacing much later
     * as "no schema configured" for a name the operator can see written in their own config.
     */
    private static void requireQualifiedName(String name, String format, String configName) {
        final var segments = name.split("\\.", -1);
        // namespace (>=1) + format + event type
        if (segments.length < 3) {
            throw new IllegalArgumentException(configName + " key '" + name + "' must be <namespace>." + format
                    + ".<EventType> - a namespace, the format segment '" + format + "', then the event type");
        }
        for (final var segment : segments) {
            if (!IDENTIFIER.matcher(segment).matches()) {
                throw new IllegalArgumentException(configName + " key '" + name + "' has segment '" + segment
                        + "' that is not an identifier; keys are dot-separated identifiers, like a Java package");
            }
        }
        final var actualFormat = segments[segments.length - 2];
        if (!format.equals(actualFormat)) {
            throw new IllegalArgumentException(configName + " key '" + name + "' has format segment '" + actualFormat
                    + "' but this transform emits " + format + "; the key must be <namespace>." + format
                    + ".<EventType>");
        }
    }
}
