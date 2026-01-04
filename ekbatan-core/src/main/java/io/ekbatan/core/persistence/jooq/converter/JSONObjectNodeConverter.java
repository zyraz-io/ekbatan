package io.ekbatan.core.persistence.jooq.converter;

import org.jooq.Converter;
import org.jooq.JSON;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class JSONObjectNodeConverter implements Converter<JSON, ObjectNode> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public ObjectNode from(JSON databaseObject) {
        if (databaseObject == null || databaseObject.data() == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(databaseObject.data(), ObjectNode.class);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to convert JSON to ObjectNode", e);
        }
    }

    @Override
    public JSON to(ObjectNode userObject) {
        if (userObject == null) {
            return null;
        }
        try {
            return JSON.valueOf(OBJECT_MAPPER.writeValueAsString(userObject));
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to convert ObjectNode to JSON", e);
        }
    }

    @Override
    public Class<JSON> fromType() {
        return JSON.class;
    }

    @Override
    public Class<ObjectNode> toType() {
        return ObjectNode.class;
    }
}