package io.ekbatan.bootstrap;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.distributedjobs.DistributedJob;
import io.ekbatan.distributedjobs.JobRegistry;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
import java.util.Collection;
import java.util.List;

/**
 * Framework-neutral helpers that translate {@code List<T>} bean lists supplied by a DI container
 * (Spring, Quarkus, Micronaut) into Ekbatan registries.
 */
public final class RegistryAssembler {

    private RegistryAssembler() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ActionRegistry actionRegistry(Collection<? extends Action<?, ?>> actions) {
        ActionRegistry.Builder b = ActionRegistry.Builder.actionRegistry();
        for (var action : actions) {
            // Raw call: ActionRegistry.withAction is <P,R,A extends Action<P,R>>(Class<A>, A);
            // we don't have concrete P/R at this point. Erasure makes this safe at runtime.
            Class cls = action.getClass();
            b.withAction(cls, action);
        }
        return b.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static RepositoryRegistry repositoryRegistry(List<? extends AbstractRepository<?, ?, ?, ?>> repositories) {
        var b = RepositoryRegistry.Builder.repositoryRegistry();
        for (var repo : repositories) {
            Class<? extends Persistable<?>> domain = repo.domainClass;
            if (Model.class.isAssignableFrom(domain)) {
                b.withModelRepository((Class) domain, (AbstractRepository) repo);
            } else if (Entity.class.isAssignableFrom(domain)) {
                b.withEntityRepository((Class) domain, (AbstractRepository) repo);
            } else {
                throw new IllegalStateException("Repository domainClass " + domain.getName()
                        + " is neither a Model nor an Entity — "
                        + repo.getClass().getName()
                        + " cannot be registered.");
            }
        }
        return b.build();
    }

    public static EventHandlerRegistry eventHandlerRegistry(List<? extends EventHandler<?>> handlers) {
        var b = EventHandlerRegistry.eventHandlerRegistry();
        handlers.forEach(b::withHandler);
        return b.build();
    }

    public static JobRegistry jobRegistry(JobRegistry.Builder partial, List<? extends DistributedJob> jobs) {
        jobs.forEach(partial::withJob);
        return partial.build();
    }
}
