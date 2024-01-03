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

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.grpc.Status;
import io.netty.util.concurrent.EventExecutor;

abstract class AbstractNode<T extends Snapshot<? extends ResourceHolder>>
        implements SnapshotWatcher<T>, SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AbstractNode.class);

    private final XdsBootstrapImpl xdsBootstrap;
    private final EventExecutor eventLoop;
    @Nullable
    private T snapshot;
    private final Set<SnapshotWatcher<? super T>> snapshotWatchers = new HashSet<>();
    private boolean closed;

    AbstractNode(XdsBootstrapImpl xdsBootstrap) {
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
        if (!eventLoop().inEventLoop()) {
            eventLoop().execute(() -> removeSnapshotWatcher(watcher));
            return;
        }
        snapshotWatchers.remove(watcher);
    }

    @Override
    public void snapshotUpdated(T child) {
        snapshot = child;
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
