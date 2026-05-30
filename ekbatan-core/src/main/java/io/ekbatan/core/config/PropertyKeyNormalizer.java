package io.ekbatan.core.config;

/**
 * Folds hyphen-separated property key segments to camelCase so configuration written in either
 * casing convention binds the same way. Used by each DI's {@code EkbatanCoreConfiguration} when
 * copying flat property keys into the {@code Properties} fed to {@code JavaPropsMapper}: kebab
 * keys ({@code default-shard.group}, {@code configs.primary-config.jdbc-url}) become camelCase
 * ({@code defaultShard.group}, {@code configs.primaryConfig.jdbcUrl}), so Jackson can match
 * verbatim against builder method names and the {@code configs} map's reserved keys
 * ({@code primaryConfig}, {@code secondaryConfig}, ...) without a custom map-key deserializer or
 * a kebab-naming-strategy at the mapper level.
 *
 * <p>Values are not touched, only keys. The function is a simple state machine that uppercases
 * the character following each {@code -} (any other characters -- letters, digits, dots, brackets,
 * slashes -- pass through unchanged); it has no effect on keys that are already camelCase. Two
 * properties spelled in different cases under the same prefix collapse to the same canonical key
 * (later-iterated values override earlier ones unless callers de-duplicate first).
 */
public final class PropertyKeyNormalizer {

    private PropertyKeyNormalizer() {}

    /**
     * Translates kebab-case segments inside {@code key} to camelCase, leaving everything else
     * untouched.
     *
     * @param key the raw configuration key (any path form: dot-separated, bracket-indexed, ...).
     * @return the normalized key -- same string if no hyphens are present.
     */
    public static String kebabToCamel(String key) {
        if (key.indexOf('-') < 0) {
            return key; // fast path - already camelCase or hyphen-free
        }
        var sb = new StringBuilder(key.length());
        boolean upper = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
