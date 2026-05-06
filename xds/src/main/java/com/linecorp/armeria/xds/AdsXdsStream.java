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

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;

import io.grpc.Status;
import io.netty.util.concurrent.EventExecutor;

final class AdsXdsStream implements XdsStream {

    interface ActualStream {
        void closeStream();

        void resourcesUpdated(XdsType type);
    }

    @FunctionalInterface
    interface ActualStreamFactory {
        ActualStream create(AdsXdsStream owner);
    }

    private final ActualStreamFactory factory;
    private final Backoff backoff;
    private final EventExecutor eventLoop;
    private final StateCoordinator stateCoordinator;
    private final ConfigSourceLifecycleObserver lifecycleObserver;
    private final Set<XdsType> targetTypes;

    private int connBackoffAttempts = 1;
    private boolean stopped;
    @Nullable
    private ActualStream actualStream;

    AdsXdsStream(ActualStreamFactory factory, Backoff backoff, EventExecutor eventLoop,
                 StateCoordinator stateCoordinator, ConfigSourceLifecycleObserver lifecycleObserver,
                 Set<XdsType> targetTypes) {
        this.factory = requireNonNull(factory, "factory");
        this.backoff = requireNonNull(backoff, "backoff");
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        this.stateCoordinator = requireNonNull(stateCoordinator, "stateCoordinator");
        this.lifecycleObserver = requireNonNull(lifecycleObserver, "lifecycleObserver");
        this.targetTypes = requireNonNull(targetTypes, "targetTypes");
    }

    void stop() {
        stop(Status.CANCELLED.withDescription("shutdown").asException());
    }

    void stop(Throwable throwable) {
        requireNonNull(throwable, "throwable");
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> stop(throwable));
            return;
        }
        stopped = true;
        if (actualStream == null) {
            return;
        }
        actualStream.closeStream();
        actualStream = null;
    }

    @Override
    public void close() {
        stop();
        lifecycleObserver.close();
    }

    @Override
    public void resourcesUpdated(XdsType type) {
        ensureStream().resourcesUpdated(type);
    }

    void retryOrClose(boolean closedByError) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> retryOrClose(closedByError));
            return;
        }
        if (stopped) {
            return;
        }
        actualStream = null;
        if (closedByError) {
            connBackoffAttempts++;
        } else {
            connBackoffAttempts = 1;
        }
        final long nextDelayMillis = backoff.nextDelayMillis(connBackoffAttempts);
        eventLoop.schedule(this::reset, Math.max(nextDelayMillis, 1_000L), TimeUnit.MILLISECONDS);
    }

    private ActualStream ensureStream() {
        if (actualStream == null) {
            actualStream = factory.create(this);
        }
        return actualStream;
    }

    private void reset() {
        if (stopped) {
            return;
        }
        for (XdsType targetType : targetTypes) {
            if (!stateCoordinator.interestedResources(targetType).isEmpty()) {
                resourcesUpdated(targetType);
            }
        }
    }
}
