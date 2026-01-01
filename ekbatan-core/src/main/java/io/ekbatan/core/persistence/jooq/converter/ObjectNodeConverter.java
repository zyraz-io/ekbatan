package io.ekbatan.core.persistence.jooq.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jooq.Converter;
import org.jooq.JSONB;

public class ObjectNodeConverter implements Converter<JSONB, ObjectNode> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public ObjectNode from(JSONB databaseObject) {
        if (databaseObject == null || databaseObject.data() == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(databaseObject.data(), ObjectNode.class);
        } catch (JsonProcessingException e) {
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
        } catch (JsonProcessingException e) {
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
