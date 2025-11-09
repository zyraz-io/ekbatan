package com.example.springdd.core.domain;

/**
 * Interface that defines a contract for objects that can be uniquely identified.
 *
 * @param <ID> The type of the ID that uniquely identifies the object.
 */
public interface Identifiable<ID> {

    /**
     * Returns the unique identifier of the object.
     *
     * @return the ID of the object
     */
    ID getId();
}
