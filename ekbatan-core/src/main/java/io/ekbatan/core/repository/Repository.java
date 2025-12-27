package io.ekbatan.core.repository;

import io.ekbatan.core.domain.Persistable;
import java.util.Collection;
import java.util.List;

/**
 * Marker interface for all repositories
 *
 */
public interface Repository<PERSISTABLE extends Persistable<?>> {

    PERSISTABLE add(PERSISTABLE model);

    void addNoResult(PERSISTABLE model);

    List<PERSISTABLE> addAll(Collection<PERSISTABLE> models);

    void addAllNoResult(Collection<PERSISTABLE> models);

    PERSISTABLE update(PERSISTABLE model);

    void updateNoResult(PERSISTABLE model);

    List<PERSISTABLE> updateAll(Collection<PERSISTABLE> models);

    void updateAllNoResult(Collection<PERSISTABLE> models);

    List<PERSISTABLE> findAll();
}
