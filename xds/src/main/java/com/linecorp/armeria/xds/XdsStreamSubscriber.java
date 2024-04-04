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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.grpc.Status;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

class XdsStreamSubscriber<T extends XdsResource> implements SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(XdsStreamSubscriber.class);

    private final XdsType type;
    private final String resource;
    private final long timeoutMillis;
    private final EventExecutor eventLoop;

    @Nullable
    private T data;
    private boolean absent;
    @Nullable
    private ScheduledFuture<?> initialAbsentFuture;
    private final Set<ResourceWatcher<T>> resourceWatchers = new HashSet<>();

    XdsStreamSubscriber(XdsType type, String resource, EventExecutor eventLoop, long timeoutMillis) {
        this.type = type;
        this.resource = resource;
        this.eventLoop = eventLoop;
        this.timeoutMillis = timeoutMillis;

        restartTimer();
    }

    void restartTimer() {
        if (data != null || absent) {  // resource already resolved
            return;
        }

        initialAbsentFuture = eventLoop.schedule(() -> {
            initialAbsentFuture = null;
            onAbsent();
        }, timeoutMillis, TimeUnit.MILLISECONDS);
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

    void onData(T data) {
        maybeCancelAbsentTimer();

        final T oldData = this.data;
        this.data = data;
        absent = false;
        if (!Objects.equals(oldData, data)) {
            for (ResourceWatcher<T> watcher: resourceWatchers) {
                try {
                    watcher.onChanged(data);
                } catch (Exception e) {
                    logger.warn("Unexpected exception while invoking {}.onChanged() with ({}, {}) for ({}).",
                                getClass().getSimpleName(), type, resource, data, e);
                }
            }
        }
    }

    @Nullable
    T data() {
        return data;
    }

    void onError(Status status) {
        maybeCancelAbsentTimer();
        for (ResourceWatcher<?> watcher: resourceWatchers) {
            try {
                watcher.onError(type, status);
            } catch (Exception e) {
                logger.warn("Unexpected exception while invoking {}.onError() with ({}, {}) for ({}).",
                            getClass().getSimpleName(), type, resource, status, e);
            }
        }
    }

    void onAbsent() {
        maybeCancelAbsentTimer();

        if (!absent) {
            data = null;
            absent = true;
            for (ResourceWatcher<?> watcher: resourceWatchers) {
                try {
                    watcher.onResourceDoesNotExist(type, resource);
                } catch (Exception e) {
                    logger.warn("Unexpected exception while invoking" +
                                " {}.onResourceDoesNotExist() with ({}, {}).",
                                getClass().getSimpleName(), type, resource, e);
                }
            }
        }
    }

    boolean isEmpty() {
        return resourceWatchers.isEmpty();
    }

    void registerWatcher(ResourceWatcher<T> watcher) {
        resourceWatchers.add(watcher);
        if (data != null) {
            watcher.onChanged(data);
        } else if (absent) {
            watcher.onResourceDoesNotExist(type, resource);
        }
    }

    void unregisterWatcher(ResourceWatcher<?> watcher) {
        resourceWatchers.remove(watcher);
    }
}
