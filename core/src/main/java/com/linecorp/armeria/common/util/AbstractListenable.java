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

import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.IdentityHashStrategy;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenCustomHashSet;

/**
 * A skeletal {@link Listenable} implementation.
 */
public abstract class AbstractListenable<T> implements Listenable<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<AbstractListenable, Set> listenersUpdater =
            AtomicReferenceFieldUpdater.newUpdater(AbstractListenable.class, Set.class, "listeners");

    // Updated via updateListenersUpdater
    private volatile Set<Consumer<? super T>> listeners = ImmutableSet.of();

    /**
     * Notify the new value changes to the listeners added via {@link #addListener(Consumer)}.
     */
    protected final void notifyListeners(T latestValue) {
        final Set<Consumer<? super T>> listeners = this.listeners;
        listeners.forEach(listener -> listener.accept(latestValue));
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
        for (;;) {
            final Set<Consumer<? super T>> listeners = this.listeners;
            final Set<Consumer<? super T>> newListeners = newIdentitySet(listeners);
            newListeners.add(listener);
            if (listenersUpdater.compareAndSet(this, listeners, newListeners)) {
                if (notifyLatestValue) {
                    final T latest = latestValue();
                    if (latest != null) {
                        listener.accept(latest);
                    }
                }
                break;
            }
        }
    }

    @Override
    public final void removeListener(Consumer<?> listener) {
        requireNonNull(listener, "listener");
        for (;;) {
            final Set<Consumer<? super T>> listeners = this.listeners;
            final Set<Consumer<? super T>> newListeners = newIdentitySet(listeners);
            newListeners.remove(listener);
            if (listenersUpdater.compareAndSet(this, listeners, newListeners)) {
                break;
            }
        }
    }

    private Set<Consumer<? super T>> newIdentitySet(Set<Consumer<? super T>> listeners) {
        if (listeners.isEmpty()) {
            return new ObjectLinkedOpenCustomHashSet<>(IdentityHashStrategy.of());
        } else {
            return new ObjectLinkedOpenCustomHashSet<>(listeners, IdentityHashStrategy.of());
        }
    }
}
