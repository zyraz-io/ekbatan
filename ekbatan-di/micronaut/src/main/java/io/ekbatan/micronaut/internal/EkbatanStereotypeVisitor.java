package io.ekbatan.micronaut.internal;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Lifts each Ekbatan stereotype to {@code @Singleton} at the user's compile time, so Micronaut's
 * AP generates a {@code BeanDefinition} for every {@code @EkbatanAction} / {@code @EkbatanRepository}
 * / {@code @EkbatanEventHandler} / {@code @EkbatanDistributedJob} class without any user-side
 * wiring.
 *
 * <p>{@link TypeElementVisitor} (not {@code AnnotationMapper}) because Micronaut 4.x's
 * incremental Gradle AP only fires for annotations declared in
 * {@code -Amicronaut.processing.annotations=…}; an {@code AnnotationMapper} keyed by a
 * third-party stereotype silently stops firing on incremental rebuilds (cf. micronaut-core#7340).
 * {@code TypeElementVisitor.getSupportedAnnotationNames()} feeds the AP's incremental-trigger
 * set automatically — same pattern as {@code micronaut-data}'s {@code RepositoryTypeElementVisitor}.
 *
 * <p>Registered <em>twice</em>:
 * {@code META-INF/services/io.micronaut.inject.visitor.TypeElementVisitor} (legacy) and
 * {@code META-INF/micronaut/io.micronaut.inject.visitor.TypeElementVisitor/...} (the per-impl
 * marker file Micronaut 4.x's SoftServiceLoader actually reads). The {@code META-INF/services}
 * path alone isn't sufficient — without the new-format file the class isn't loaded by the AP.
 */
public class EkbatanStereotypeVisitor implements TypeElementVisitor<Object, Object> {

    private static final Set<String> STEREOTYPES = Set.of(
            "io.ekbatan.di.EkbatanAction",
            "io.ekbatan.di.EkbatanRepository",
            "io.ekbatan.di.EkbatanEventHandler",
            "io.ekbatan.di.EkbatanDistributedJob");

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return STEREOTYPES;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (STEREOTYPES.stream().noneMatch(element::hasDeclaredAnnotation)) {
            return;
        }
        element.annotate(Singleton.class);
    }
}
