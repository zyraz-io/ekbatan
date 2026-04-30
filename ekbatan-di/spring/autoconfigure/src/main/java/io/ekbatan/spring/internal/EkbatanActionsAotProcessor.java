package io.ekbatan.spring.internal;

import io.ekbatan.core.action.Action;
import io.ekbatan.di.EkbatanAction;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.Modifier;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.javapoet.CodeBlock;
import org.springframework.util.ClassUtils;

/**
 * Spring AOT processor that discovers {@code @EkbatanAction}-annotated classes at AOT
 * processing time and emits an initializer that registers them in
 * {@link EkbatanActionsHolder} before the bean factory is post-processed at runtime.
 *
 * <p><b>Why</b>: {@code EkbatanCoreConfiguration} historically scanned for
 * {@code @EkbatanAction} via {@link ClassPathScanningCandidateComponentProvider} at
 * runtime. That works on the JVM (it walks {@code .class} files on the classpath) but
 * silently returns an empty set on GraalVM native image — there are no {@code .class}
 * files to walk, so {@code ActionRegistry.get(...)} fails with
 * {@code "No action registered for class"} for every action invocation.
 *
 * <p>This processor moves the scan to AOT processing time (run by Spring's
 * {@code processAot} / {@code processTestAot} Gradle tasks on the JVM), records the
 * discovered classes via {@code ekbatan.aot.actions=…} in the generated initializer,
 * and registers reflection hints so the action classes' constructors remain invokable
 * via {@code AutowireCapableBeanFactory.createBean(Class)} at runtime.
 *
 * <p>Registered via {@code META-INF/spring/aot.factories}.
 */
public class EkbatanActionsAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        Set<Class<? extends Action<?, ?>>> actionClasses = scanActionClasses(beanFactory);
        if (actionClasses.isEmpty()) {
            return null;
        }
        return new Contribution(actionClasses);
    }

    @SuppressWarnings("unchecked")
    private static Set<Class<? extends Action<?, ?>>> scanActionClasses(ConfigurableListableBeanFactory beanFactory) {
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return Set.of();
        }
        var packages = AutoConfigurationPackages.get(beanFactory);
        // The processor runs on the JVM at AOT processing time, so the standard scanner
        // can read .class files normally — same code path as the runtime fallback in
        // EkbatanCoreConfiguration.
        var scanner = new ClassPathScanningCandidateComponentProvider(false, new StandardEnvironment());
        scanner.addIncludeFilter(new AnnotationTypeFilter(EkbatanAction.class));
        var classes = new LinkedHashSet<Class<? extends Action<?, ?>>>();
        for (var pkg : packages) {
            for (var bd : scanner.findCandidateComponents(pkg)) {
                Class<?> cls = ClassUtils.resolveClassName(bd.getBeanClassName(), null);
                if (!Action.class.isAssignableFrom(cls)) {
                    throw new IllegalStateException(
                            "@EkbatanAction on " + cls.getName() + " — class must extend " + Action.class.getName());
                }
                classes.add((Class<? extends Action<?, ?>>) cls);
            }
        }
        return classes;
    }

    private static final class Contribution implements BeanFactoryInitializationAotContribution {

        private final Set<Class<? extends Action<?, ?>>> actionClasses;

        Contribution(Set<Class<? extends Action<?, ?>>> actionClasses) {
            this.actionClasses = actionClasses;
        }

        @Override
        public void applyTo(GenerationContext generationContext, BeanFactoryInitializationCode initializationCode) {
            // Reflection hints so AutowireCapableBeanFactory.createBean(Class) can invoke
            // each action's constructor at native runtime.
            var reflection = generationContext.getRuntimeHints().reflection();
            for (Class<?> cls : actionClasses) {
                reflection.registerType(
                        cls,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.DECLARED_FIELDS);
            }

            // Generate a private static method on the AOT-generated bean factory initializer
            // class that calls EkbatanActionsHolder.set(WidgetCreateAction.class, ...).
            var method = initializationCode.getMethods().add("registerEkbatanActions", builder -> {
                builder.addModifiers(Modifier.PRIVATE, Modifier.STATIC);
                builder.addParameter(
                        org.springframework.beans.factory.support.DefaultListableBeanFactory.class, "beanFactory");
                CodeBlock.Builder body = CodeBlock.builder();
                body.add("$T.set(", EkbatanActionsHolder.class);
                boolean first = true;
                for (Class<?> cls : actionClasses) {
                    if (!first) {
                        body.add(", ");
                    }
                    body.add("$T.class", cls);
                    first = false;
                }
                body.add(");\n");
                builder.addCode(body.build());
            });
            initializationCode.addInitializer(method.toMethodReference());
        }
    }
}
