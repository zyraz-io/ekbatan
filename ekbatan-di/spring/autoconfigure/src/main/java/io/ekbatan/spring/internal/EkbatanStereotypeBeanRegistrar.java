package io.ekbatan.spring.internal;

import io.ekbatan.di.EkbatanAction;
import io.ekbatan.di.EkbatanDistributedJob;
import io.ekbatan.di.EkbatanEventHandler;
import io.ekbatan.di.EkbatanRepository;
import java.lang.annotation.Annotation;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Registers user classes annotated with one of Ekbatan's singleton stereotypes
 * ({@link EkbatanRepository}, {@link EkbatanEventHandler}, {@link EkbatanDistributedJob})
 * as Spring beans.
 *
 * <p>{@link EkbatanAction} is intentionally <em>not</em> handled here: actions are
 * framework-private singletons constructed via {@code AutowireCapableBeanFactory.createBean(...)}
 * in {@code EkbatanCoreConfiguration#ekbatanActionRegistry}, not Spring-managed beans.
 */
public final class EkbatanStereotypeBeanRegistrar
        implements ImportBeanDefinitionRegistrar, BeanFactoryAware, EnvironmentAware {

    private static final List<Class<? extends Annotation>> SINGLETON_STEREOTYPES =
            List.of(EkbatanRepository.class, EkbatanEventHandler.class, EkbatanDistributedJob.class);

    private BeanFactory beanFactory;
    private Environment environment;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return;
        }
        var packages = AutoConfigurationPackages.get(beanFactory);
        var scanner = new ClassPathBeanDefinitionScanner(registry, false, environment);
        SINGLETON_STEREOTYPES.forEach(s -> scanner.addIncludeFilter(new AnnotationTypeFilter(s)));
        packages.forEach(scanner::scan);
    }
}
