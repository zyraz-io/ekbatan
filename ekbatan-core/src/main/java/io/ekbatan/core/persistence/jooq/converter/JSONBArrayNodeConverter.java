package io.ekbatan.core.persistence.jooq.converter;

import org.jooq.Converter;
import org.jooq.JSONB;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * jOOQ converter mapping Postgres {@code JSONB} to Jackson 3's {@link ArrayNode}. Use for
 * array-typed JSONB columns; for object-typed columns use {@link JSONBObjectNodeConverter}.
 */
public class JSONBArrayNodeConverter implements Converter<JSONB, ArrayNode> {

    /** Constructs the converter; jOOQ instantiates it reflectively when wired through {@code forcedTypes}. */
    public JSONBArrayNodeConverter() {}

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public ArrayNode from(JSONB databaseObject) {
        if (databaseObject == null || databaseObject.data() == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(databaseObject.data(), ArrayNode.class);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to convert JSONB to ArrayNode", e);
        }
    }

    @Override
    public JSONB to(ArrayNode userObject) {
        if (userObject == null) {
            return null;
        }
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(userObject));
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to convert ArrayNode to JSONB", e);
        }
    }

    @Override
    public Class<JSONB> fromType() {
        return JSONB.class;
    }

    @Override
    public Class<ArrayNode> toType() {
        return ArrayNode.class;
    }
}
