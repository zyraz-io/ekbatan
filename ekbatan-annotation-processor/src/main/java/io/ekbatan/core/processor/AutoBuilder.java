package io.ekbatan.core.processor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for code generation. Apply to a {@code Model} or {@code Entity} subclass to make
 * {@link AutoBuilderProcessor} emit a sibling {@code <ClassName>Builder} that threads the
 * framework's required fields (id, state, version, timestamps) into your user-defined fields.
 *
 * <p>Source-retention: discarded after compilation and never appears in the resulting class
 * files - the only artifact is the generated builder source.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface AutoBuilder {}
