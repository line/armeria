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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.grpc.Status;
import io.netty.util.concurrent.EventExecutor;

abstract class AbstractRoot<T extends Snapshot<? extends XdsResource>>
        implements SnapshotWatcher<T>, SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRoot.class);
    private final EventExecutor eventLoop;
    @Nullable
    private T snapshot;
    private final Set<SnapshotWatcher<? super T>> snapshotWatchers = new HashSet<>();
    private boolean closed;

    AbstractRoot(EventExecutor eventLoop) {
        this.eventLoop = eventLoop;
    }

    /**
     * The event loop used to notify updates to {@link SnapshotWatcher}s.
     */
    public final EventExecutor eventLoop() {
        return eventLoop;
    }

    /**
     * Adds a watcher which waits for a snapshot update.
     */
    public void addSnapshotWatcher(SnapshotWatcher<? super T> watcher) {
        requireNonNull(watcher, "watcher");
        checkState(!closed, "Watcher %s can't be registered since %s is already closed.",
                   watcher, getClass().getSimpleName());
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> addSnapshotWatcher(watcher));
            return;
        }
        if (closed) {
            return;
        }
        snapshotWatchers.add(watcher);
        if (snapshot != null) {
            try {
                watcher.snapshotUpdated(snapshot);
            } catch (Throwable t) {
                logger.warn("Unexpected exception while invoking {}.snapshotUpdated",
                            watcher.getClass().getSimpleName(), t);
            }
        }
    }

    /**
     * Removes a watcher which waits for a snapshot update.
     */
    public void removeSnapshotWatcher(SnapshotWatcher<? super T> watcher) {
        requireNonNull(watcher, "watcher");
        checkState(!closed, "Watcher %s can't be removed since %s is already closed.",
                   watcher, getClass().getSimpleName());
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeSnapshotWatcher(watcher));
            return;
        }
        if (closed) {
            return;
        }
        snapshotWatchers.remove(watcher);
    }

    @Override
    public void snapshotUpdated(T newSnapshot) {
        if (closed) {
            return;
        }
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> snapshotUpdated(newSnapshot));
            return;
        }
        snapshot = newSnapshot;
        notifyWatchers("snapshotUpdated", watcher -> watcher.snapshotUpdated(newSnapshot));
    }

    @Override
    public void onMissing(XdsType type, String resourceName) {
        if (closed) {
            return;
        }
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> onMissing(type, resourceName));
            return;
        }
        notifyWatchers("onMissing", watcher -> watcher.onMissing(type, resourceName));
    }

    @Override
    public void onError(XdsType type, Status status) {
        if (closed) {
            return;
        }
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> onError(type, status));
            return;
        }
        notifyWatchers("onError", watcher -> watcher.onError(type, status));
    }

    private void notifyWatchers(String methodName, Consumer<SnapshotWatcher<? super T>> consumer) {
        for (SnapshotWatcher<? super T> watcher: snapshotWatchers) {
            try {
                consumer.accept(watcher);
            } catch (Throwable t) {
                logger.warn("Unexpected exception while invoking {}.{}",
                            watcher.getClass().getSimpleName(), methodName, t);
            }
        }
    }

    @Nullable
    @VisibleForTesting
    T current() {
        return snapshot;
    }

    @VisibleForTesting
    boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
