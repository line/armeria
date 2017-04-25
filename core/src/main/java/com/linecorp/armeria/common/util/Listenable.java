package com.linecorp.armeria.common.util;

import java.util.function.Consumer;

/**
 * An interface that accepts item change listeners.
 */
public interface Listenable<T> {
    /**
     * Adds a {@link Consumer} that will be invoked when a {@link Listenable} changes its value.
     */
    void addListener(Consumer<? super T> listener);

    /**
     * Remove a listener.
     */
    void removeListener(Consumer<?> listener);
}
