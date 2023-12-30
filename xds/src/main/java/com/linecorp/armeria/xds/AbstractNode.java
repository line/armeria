/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.Nullable;

import io.grpc.Status;
import io.netty.util.concurrent.EventExecutor;

abstract class AbstractNode<T> implements ResourceWatcher<T> {

    private final WatchersStorage watchersStorage;
    private final EventExecutor eventLoop;
    private final CompletableFuture<Void> whenReady = new CompletableFuture<>();
    private final Set<ResourceWatcher<? super T>> listeners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    @Nullable
    private volatile T current;

    AbstractNode(WatchersStorage watchersStorage) {
        this.watchersStorage = watchersStorage;
        eventLoop = watchersStorage().eventLoop();
    }

    /**
     * Returns the latest value of this node.
     */
    @Nullable
    public final T current() {
        return current;
    }

    @Override
    public final void onChanged(T update) {
        current = update;
        if (!whenReady.isDone()) {
            whenReady.complete(null);
        }
        for (ResourceWatcher<? super T> watcher: listeners) {
            watcher.onChanged(update);
        }
    }

    @Override
    public void onResourceDoesNotExist(XdsType type, String resourceName) {
        current = null;
        if (!whenReady.isDone()) {
            whenReady.complete(null);
        }
        for (ResourceWatcher<? super T> watcher: listeners) {
            watcher.onResourceDoesNotExist(type, resourceName);
        }
    }

    @Override
    public void onError(XdsType type, Status error) {
        for (ResourceWatcher<? super T> watcher: listeners) {
            watcher.onError(type, error);
        }
    }

    /**
     * Adds a listener which is notified when this node is updated.
     */
    public final void addListener(ResourceWatcher<? super T> listener) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> addListener(listener));
            return;
        }
        if (listeners.add(listener) && current != null) {
            listener.onChanged(current);
        }
    }

    /**
     * Removes a listener which is notified when this node is updated.
     */
    public final void removeListener(ResourceWatcher<? super T> listener) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeListener(listener));
            return;
        }
        listeners.remove(listener);
    }

    /**
     * Returns a {@link CompletableFuture} which is completed when the initial value is set.
     */
    public CompletableFuture<Void> whenReady() {
        return whenReady;
    }

    final WatchersStorage watchersStorage() {
        return watchersStorage;
    }

    final EventExecutor eventLoop() {
        return eventLoop;
    }
}
