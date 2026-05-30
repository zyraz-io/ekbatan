package io.ekbatan.core.config;

import static io.ekbatan.core.config.PropertyKeyNormalizer.kebabToCamel;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Covers the behaviour callers of {@link PropertyKeyNormalizer} actually rely on: kebab -> camel
 * translation, camelCase pass-through, and the structural characters (brackets, dots, colons)
 * that show up in our property key paths must not be disturbed.
 */
class PropertyKeyNormalizerTest {

    @Test
    void leavesPlainCamelCaseUntouched() {
        assertThat(kebabToCamel("defaultShard")).isEqualTo("defaultShard");
        assertThat(kebabToCamel("jdbcUrl")).isEqualTo("jdbcUrl");
        assertThat(kebabToCamel("groups[0].members[0]")).isEqualTo("groups[0].members[0]");
    }

    @Test
    void leavesEmptyAndSingleSegmentUntouched() {
        assertThat(kebabToCamel("")).isEqualTo("");
        assertThat(kebabToCamel("group")).isEqualTo("group");
    }

    @Test
    void foldsSingleHyphen() {
        assertThat(kebabToCamel("default-shard")).isEqualTo("defaultShard");
        assertThat(kebabToCamel("jdbc-url")).isEqualTo("jdbcUrl");
    }

    @Test
    void foldsMultipleHyphensPerSegment() {
        assertThat(kebabToCamel("handling-max-backoff-cap")).isEqualTo("handlingMaxBackoffCap");
        assertThat(kebabToCamel("handling-retention-window")).isEqualTo("handlingRetentionWindow");
    }

    @Test
    void preservesDotSeparatedPaths() {
        // The dot separator and bracket-index markers are structural, not casing - leave them alone.
        assertThat(kebabToCamel("default-shard.group")).isEqualTo("defaultShard.group");
        assertThat(kebabToCamel("groups[0].members[0].configs.primary-config.jdbc-url"))
                .isEqualTo("groups[0].members[0].configs.primaryConfig.jdbcUrl");
    }

    @Test
    void handlesMixedKebabAndCamelInSamePath() {
        // Mixed-form paths are the whole point of the helper - both shapes resolve to the same
        // canonical camelCase string.
        assertThat(kebabToCamel("default-shard.member")).isEqualTo("defaultShard.member");
        assertThat(kebabToCamel("defaultShard.member")).isEqualTo("defaultShard.member");
        assertThat(kebabToCamel("configs.primary-config.jdbcUrl")).isEqualTo("configs.primaryConfig.jdbcUrl");
        assertThat(kebabToCamel("configs.primaryConfig.jdbc-url")).isEqualTo("configs.primaryConfig.jdbcUrl");
    }

    @Test
    void uppercasesOnlyTheCharacterAfterTheHyphen() {
        // The state machine only uppercases the immediate next character; subsequent letters
        // stay as written. Confirms we don't accidentally upper-case the whole tail.
        assertThat(kebabToCamel("a-bc")).isEqualTo("aBc");
        assertThat(kebabToCamel("foo-barBaz")).isEqualTo("fooBarBaz");
    }

    @Test
    void leavesNumericSegmentsAlone() {
        // Property paths can include numeric suffixes (e.g., wallet shard names primary-eu-west-1)
        // - the digit after the hyphen survives as-is.
        assertThat(kebabToCamel("primary-eu-west-1")).isEqualTo("primaryEuWest1");
    }
}
