package io.ekbatan.core.persistence.jooq.converter;

import org.jooq.Converter;
import org.jooq.JSONB;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * jOOQ converter mapping Postgres {@code JSONB} to Jackson 3's {@link ObjectNode}. Use for
 * object-typed JSONB columns; for array-typed columns use {@link JSONBArrayNodeConverter}.
 *
 * <p>For MySQL / MariaDB {@code JSON} columns, use {@link JSONObjectNodeConverter} - the
 * jOOQ wrapper type differs.
 */
public class JSONBObjectNodeConverter implements Converter<JSONB, ObjectNode> {

    /** Constructs the converter; jOOQ instantiates it reflectively when wired through {@code forcedTypes}. */
    public JSONBObjectNodeConverter() {}

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public ObjectNode from(JSONB databaseObject) {
        if (databaseObject == null || databaseObject.data() == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(databaseObject.data(), ObjectNode.class);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to convert JSONB to ObjectNode", e);
        }
    }

    @Override
    public JSONB to(ObjectNode userObject) {
        if (userObject == null) {
            return null;
        }
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(userObject));
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to convert ObjectNode to JSONB", e);
        }
    }

    @Override
    public Class<JSONB> fromType() {
        return JSONB.class;
    }

    @Override
    public Class<ObjectNode> toType() {
        return ObjectNode.class;
    }
}
