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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.grpc.Status;
import io.netty.util.concurrent.EventExecutor;

abstract class AbstractRoot<T extends Snapshot<? extends ResourceHolder>>
        implements SnapshotWatcher<T>, SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRoot.class);

    private final XdsBootstrapImpl xdsBootstrap;
    private final EventExecutor eventLoop;
    @Nullable
    private T snapshot;
    private final Set<SnapshotWatcher<? super T>> snapshotWatchers = new HashSet<>();
    private boolean closed;

    AbstractRoot(XdsBootstrapImpl xdsBootstrap) {
        this.xdsBootstrap = xdsBootstrap;
        eventLoop = xdsBootstrap.eventLoop();
    }

    final XdsBootstrapImpl xdsBootstrap() {
        return xdsBootstrap;
    }

    final EventExecutor eventLoop() {
        return eventLoop;
    }

    public void addSnapshotWatcher(SnapshotWatcher<? super T> watcher) {
        requireNonNull(watcher, "watcher");
        checkState(!closed, "Watcher %s can't be registered since %s is already closed.",
                   watcher, getClass().getSimpleName());
        if (!eventLoop().inEventLoop()) {
            eventLoop().execute(() -> addSnapshotWatcher(watcher));
            return;
        }
        snapshotWatchers.add(watcher);
        if (snapshot != null) {
            try {
                watcher.snapshotUpdated(snapshot);
            } catch (Throwable t) {
                logger.warn("Unexpected exception while invoking {}.onChanged",
                            watcher.getClass().getSimpleName(), t);
            }
        }
    }

    public void removeSnapshotWatcher(SnapshotWatcher<? super T> watcher) {
        requireNonNull(watcher, "watcher");
        checkState(!closed, "Watcher %s can't be removed since %s is already closed.",
                   watcher, getClass().getSimpleName());
        if (!eventLoop().inEventLoop()) {
            eventLoop().execute(() -> removeSnapshotWatcher(watcher));
            return;
        }
        snapshotWatchers.remove(watcher);
    }

    @Override
    public void snapshotUpdated(T newSnapshot) {
        snapshot = newSnapshot;
        if (closed) {
            return;
        }
        for (SnapshotWatcher<? super T> watcher: snapshotWatchers) {
            try {
                watcher.snapshotUpdated(snapshot);
            } catch (Throwable t) {
                logger.warn("Unexpected exception while invoking {}.onChanged",
                            watcher.getClass().getSimpleName(), t);
            }
        }
    }

    @Override
    public void onMissing(XdsType type, String resourceName) {
        if (closed) {
            return;
        }
        for (SnapshotWatcher<? super T> watcher: snapshotWatchers) {
            try {
                watcher.onMissing(type, resourceName);
            } catch (Throwable t) {
                logger.warn("Unexpected exception while invoking {}.onChanged",
                            watcher.getClass().getSimpleName(), t);
            }
        }
    }

    @Override
    public void onError(XdsType type, Status status) {
        if (closed) {
            return;
        }
        for (SnapshotWatcher<? super T> watcher: snapshotWatchers) {
            try {
                watcher.onError(type, status);
            } catch (Throwable t) {
                logger.warn("Unexpected exception while invoking {}.onChanged",
                            watcher.getClass().getSimpleName(), t);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
