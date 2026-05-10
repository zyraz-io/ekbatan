package io.ekbatan.quarkus.deployment;

import io.ekbatan.di.EkbatanAction;
import io.ekbatan.di.EkbatanDistributedJob;
import io.ekbatan.di.EkbatanEventHandler;
import io.ekbatan.di.EkbatanRepository;
import io.ekbatan.quarkus.runtime.EkbatanCoreConfiguration;
import io.ekbatan.quarkus.runtime.EkbatanDistributedJobsConfiguration;
import io.ekbatan.quarkus.runtime.EkbatanLocalEventHandlerConfiguration;
import io.ekbatan.quarkus.runtime.EkbatanProperties;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.ConfigMappingBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;

/**
 * Quarkus extension deployment processor for Ekbatan. Runs at Quarkus build time (jar / test
 * bootstrap) and registers the runtime {@code @Factory}-equivalent producer classes plus
 * every user-supplied {@code @EkbatanAction} / {@code @EkbatanRepository} /
 * {@code @EkbatanEventHandler} / {@code @EkbatanDistributedJob} bean discovered through the
 * Jandex index.
 *
 * <p>This is the canonical Quarkus extension entry point — Quarkus locates it via the
 * {@code META-INF/quarkus-extension.yaml} metadata in the runtime jar. Application authors
 * never reference {@code EkbatanProcessor} directly; they get its effects by depending on
 * {@code ekbatan-quarkus} and letting Quarkus's deployment phase do the rest.
 *
 * <p>The optional Ekbatan modules ({@code local-event-handler} and {@code distributed-jobs})
 * are gated via {@link QuarkusClassLoader#isClassPresentAtRuntime(String)} so users who
 * exclude those jars from their Quarkus app classpath don't pay for the producer classes.
 */
public class EkbatanProcessor {

    /** Required by Quarkus; the build engine instantiates this processor to invoke its {@code @BuildStep} methods. */
    public EkbatanProcessor() {}

    private static final String FEATURE = "ekbatan";

    private static final List<Class<? extends Annotation>> STEREOTYPES = List.of(
            EkbatanAction.class, EkbatanRepository.class, EkbatanEventHandler.class, EkbatanDistributedJob.class);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem registerCoreProducers() {
        // Class name as String (not Class<?>) so the deployment classloader doesn't resolve
        // producer method signatures referencing ShardingConfig / DatabaseRegistry / ActionExecutor
        // — that would conflict with QuarkusClassLoader's later load of the same types.
        return AdditionalBeanBuildItem.builder()
                .addBeanClass(EkbatanCoreConfiguration.class.getName())
                .setUnremovable()
                .build();
    }

    @BuildStep
    ConfigMappingBuildItem registerConfigMapping() {
        return new ConfigMappingBuildItem(EkbatanProperties.class, "ekbatan");
    }

    // ekbatan.sharding.* is bound by EkbatanCoreConfiguration#ekbatanShardingConfig (Jackson
    // hybrid path), not by @ConfigMapping. SmallRye's default validate-unknown=true would
    // otherwise throw at startup for keys under a known root prefix without a matching mapping
    // member.
    @BuildStep
    RunTimeConfigurationDefaultBuildItem disableStrictMappingValidation() {
        return new RunTimeConfigurationDefaultBuildItem("smallrye.config.mapping.validate-unknown", "false");
    }

    // Singleton-scoped Actions: per-call mutable state is bound by Action.runIn(...) using a
    // ScopedValue. setUnremovable() because Arc would otherwise prune classes only consumed via
    // @All List<...> aggregation.
    @BuildStep
    void discoverEkbatanBeans(
            CombinedIndexBuildItem combinedIndex, BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        var index = combinedIndex.getIndex();
        var classNames = new LinkedHashSet<String>();
        for (var stereotype : STEREOTYPES) {
            for (var ann : index.getAnnotations(DotName.createSimple(stereotype.getName()))) {
                if (ann.target().kind() == AnnotationTarget.Kind.CLASS) {
                    classNames.add(ann.target().asClass().name().toString());
                }
            }
        }
        if (classNames.isEmpty()) {
            return;
        }
        var builder = AdditionalBeanBuildItem.builder()
                .setDefaultScope(BuiltinScope.SINGLETON.getName())
                .setUnremovable();
        classNames.forEach(builder::addBeanClass);
        additionalBeans.produce(builder.build());
    }

    // QuarkusClassLoader.isClassPresentAtRuntime (not Class.forName) — the canonical Quarkus
    // idiom (cf. ScalaProcessor, VertxKotlinProcessor). Class.forName loads the class via the
    // deployment classloader, which then conflicts with the runtime classloader's later load and
    // trips a LinkageError.
    @BuildStep
    void registerLocalEventHandlerProducers(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        if (!QuarkusClassLoader.isClassPresentAtRuntime("io.ekbatan.events.localeventhandler.EventHandlerRegistry")) {
            return;
        }
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClass(EkbatanLocalEventHandlerConfiguration.class.getName())
                .setUnremovable()
                .build());
    }

    @BuildStep
    void registerDistributedJobsProducers(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        if (!QuarkusClassLoader.isClassPresentAtRuntime("io.ekbatan.distributedjobs.JobRegistry")) {
            return;
        }
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClass(EkbatanDistributedJobsConfiguration.class.getName())
                .setUnremovable()
                .build());
    }
}
