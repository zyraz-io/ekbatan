package io.ekbatan.core.persistence.jooq.converter;

import org.jooq.Converter;
import org.jooq.JSONB;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

public class JSONBArrayNodeConverter implements Converter<JSONB, ArrayNode> {

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
