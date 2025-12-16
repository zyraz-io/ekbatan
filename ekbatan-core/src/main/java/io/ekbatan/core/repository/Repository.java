package io.ekbatan.core.repository;

import io.ekbatan.core.domain.Persistable;
import java.util.Collection;
import java.util.List;

/**
 * Marker interface for all repositories
 *
 */
public interface Repository<T extends Persistable<?>> {

    T add(T model);

    void addNoResult(T model);

    List<T> addAll(Collection<T> models);

    void addAllNoResult(Collection<T> models);

    T update(T model);

    void updateNoResult(T model);

    List<T> updateAll(Collection<T> models);

    void updateAllNoResult(Collection<T> models);

    List<T> findAll();
}
