package io.ekbatan.core.test.model;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Maps {@link Dummy#aliases} between the domain's {@code List<String>} and the {@code ArrayNode}
 * the generated record exposes for the JSON/JSONB {@code aliases} column.
 *
 * <p>The column exists so the repository tests have a value that can only reach the database
 * through a jOOQ {@code Converter}. A write path that binds from the value's runtime class rather
 * than from the target field discards that converter, which is precisely what these helpers make
 * observable.
 */
public final class Aliases {

    private Aliases() {}

    /**
     * Converts the domain representation into the record representation.
     *
     * @param aliases the domain value; may be null or empty.
     * @return an {@link ArrayNode}, or null when there is nothing to store.
     */
    public static ArrayNode toArrayNode(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        final var node = JsonNodeFactory.instance.arrayNode(aliases.size());
        aliases.forEach(node::add);
        return node;
    }

    /**
     * Converts the record representation back into the domain representation.
     *
     * @param node the stored JSON array; may be null.
     * @return the aliases, never null.
     */
    public static List<String> toList(ArrayNode node) {
        if (node == null) {
            return List.of();
        }
        final var aliases = new ArrayList<String>(node.size());
        node.forEach(element -> aliases.add(element.asString()));
        return List.copyOf(aliases);
    }
}
