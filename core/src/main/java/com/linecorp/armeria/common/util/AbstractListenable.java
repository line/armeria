/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A skeletal {@link Listenable} implementation.
 */
public abstract class AbstractListenable<T> implements Listenable<T> {

    private static final Map<?, Boolean> EMPTY_LISTENERS = new IdentityHashMap<>();

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<AbstractListenable, Map> listenersUpdater =
            AtomicReferenceFieldUpdater.newUpdater(AbstractListenable.class, Map.class, "listeners");

    // Updated via updateListenersUpdater
    @SuppressWarnings("unchecked")
    private volatile Map<Consumer<? super T>, Boolean> listeners =
            (Map<Consumer<? super T>, Boolean>) EMPTY_LISTENERS;

    /**
     * Notify the new value changes to the listeners added via {@link #addListener(Consumer)}.
     */
    protected final void notifyListeners(T latestValue) {
        final Map<Consumer<? super T>, Boolean> listeners = this.listeners;
        listeners.forEach((listener, unused) -> listener.accept(latestValue));
    }

    /**
     * Returns the latest value notified before.
     * {@code null} if the value has not been initialized yet or the implementation of this class cannot
     * cache it.
     */
    @Nullable
    protected T latestValue() {
        // TODO(ikhoon): Make this method abstract in Armeria 2.0 to avoid the mistake of not defining
        //               the latest value in subclasses.
        return null;
    }

    @Override
    public final void addListener(Consumer<? super T> listener) {
        addListener(listener, false);
    }

    /**
     * Adds a {@link Consumer} that will be invoked when a {@link Listenable} changes its value.
     * If {@code notifyLatestValue} is set to true and the {@link #latestValue()} is not null,
     * the {@link Consumer} will be invoked immediately with the {@link #latestValue()}.
     */
    public final void addListener(Consumer<? super T> listener, boolean notifyLatestValue) {
        requireNonNull(listener, "listener");
        if (notifyLatestValue) {
            final T latest = latestValue();
            if (latest != null) {
                listener.accept(latest);
            }
        }
        for (;;) {
            final Map<Consumer<? super T>, Boolean> listeners = this.listeners;
            final Map<Consumer<? super T>, Boolean> newListeners = new IdentityHashMap<>(listeners);
            newListeners.put(listener, true);
            if (listenersUpdater.compareAndSet(this, listeners, newListeners)) {
                break;
            }
        }
    }

    @Override
    public final void removeListener(Consumer<?> listener) {
        requireNonNull(listener, "listener");
        for (;;) {
            final Map<Consumer<? super T>, Boolean> listeners = this.listeners;
            final Map<Consumer<? super T>, Boolean> newListeners = new IdentityHashMap<>(listeners);
            newListeners.remove(listener);
            if (listenersUpdater.compareAndSet(this, listeners, newListeners)) {
                break;
            }
        }
    }
}
