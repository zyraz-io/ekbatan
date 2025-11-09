package com.example.springdd.core.action;

/**
 * Marker interface for actions that only read data and do not modify any state.
 * Actions implementing this interface will be executed in a read-only transaction.
 */
public interface ReadOnly {
    // Marker interface
}
