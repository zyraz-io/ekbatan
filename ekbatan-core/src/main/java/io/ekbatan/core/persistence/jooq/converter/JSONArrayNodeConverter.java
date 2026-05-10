package io.ekbatan.core.persistence.jooq.converter;

import org.jooq.Converter;
import org.jooq.JSON;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * jOOQ converter mapping SQL {@code JSON} (MySQL / MariaDB) to Jackson 3's {@link ArrayNode}.
 * Use for array-typed JSON columns; for object-typed columns use {@link JSONObjectNodeConverter}.
 */
public class JSONArrayNodeConverter implements Converter<JSON, ArrayNode> {

    /** Constructs the converter; jOOQ instantiates it reflectively when wired through {@code forcedTypes}. */
    public JSONArrayNodeConverter() {}

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public ArrayNode from(JSON databaseObject) {
        if (databaseObject == null || databaseObject.data() == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(databaseObject.data(), ArrayNode.class);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to convert JSON to ArrayNode", e);
        }
    }

    @Override
    public JSON to(ArrayNode userObject) {
        if (userObject == null) {
            return null;
        }
        try {
            return JSON.valueOf(OBJECT_MAPPER.writeValueAsString(userObject));
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to convert ArrayNode to JSON", e);
        }
    }

    @Override
    public Class<JSON> fromType() {
        return JSON.class;
    }

    @Override
    public Class<ArrayNode> toType() {
        return ArrayNode.class;
    }
}
