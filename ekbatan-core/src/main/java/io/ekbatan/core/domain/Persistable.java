package io.ekbatan.core.domain;

public interface Persistable<ID extends Comparable<?>> extends Identifiable<ID> {
    boolean isModel();

    <E extends Persistable<ID>> E nextVersion();
}
