/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * A composite watcher that fans out resource updates to multiple {@link SnapshotWatcher}s.
 * Also manages the absent-on-timeout timer for SotW protocols.
 */
class CompositeSnapshotWatcher<T extends XdsResource> implements SnapshotWatcher<T>, SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CompositeSnapshotWatcher.class);

    private final XdsType type;
    private final String resource;
    @Nullable
    private ScheduledFuture<?> initialAbsentFuture;
    private final Set<SnapshotWatcher<? super T>> watchers = new HashSet<>();

    CompositeSnapshotWatcher(XdsType type, String resource, EventExecutor eventLoop, long timeoutMillis,
                             boolean enableAbsentOnTimeout) {
        this.type = type;
        this.resource = resource;

        if (enableAbsentOnTimeout) {
            initialAbsentFuture = eventLoop.schedule(() -> {
                initialAbsentFuture = null;
                onUpdate(null, new MissingXdsResourceException(type, resource));
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void maybeCancelAbsentTimer() {
        if (initialAbsentFuture != null && initialAbsentFuture.isCancellable()) {
            initialAbsentFuture.cancel(false);
            initialAbsentFuture = null;
        }
    }

    @Override
    public void close() {
        maybeCancelAbsentTimer();
    }

    void addWatcher(SnapshotWatcher<? super T> watcher) {
        watchers.add(watcher);
    }

    void removeWatcher(SnapshotWatcher<? super T> watcher) {
        watchers.remove(watcher);
    }

    @Override
    public void onUpdate(@Nullable T value, @Nullable Throwable error) {
        maybeCancelAbsentTimer();
        for (SnapshotWatcher<? super T> watcher : watchers) {
            try {
                watcher.onUpdate(value, error);
            } catch (Exception e) {
                logger.warn("Unexpected exception while invoking onUpdate() with ({}, {}).",
                            type, resource, e);
            }
        }
    }

    boolean isEmpty() {
        return watchers.isEmpty();
    }
}
