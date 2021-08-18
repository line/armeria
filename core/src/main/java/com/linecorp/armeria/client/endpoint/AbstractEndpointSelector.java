/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * A skeletal {@link EndpointSelector} implementation. This abstract class implements the
 * {@link #select(ClientRequestContext, ScheduledExecutorService, long)} method by listening to
 * the change events emitted by {@link EndpointGroup} specified at construction time.
 */
public abstract class AbstractEndpointSelector implements EndpointSelector {

    private final EndpointGroup endpointGroup;

    /**
     * Creates a new instance that selects an {@link Endpoint} from the specified {@link EndpointGroup}.
     */
    protected AbstractEndpointSelector(EndpointGroup endpointGroup) {
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
    }

    /**
     * Returns the {@link EndpointGroup} being selected by this {@link EndpointSelector}.
     */
    protected final EndpointGroup group() {
        return endpointGroup;
    }

    @Override
    public final CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                    ScheduledExecutorService executor,
                                                    long timeoutMillis) {
        Endpoint endpoint = selectNow(ctx);
        if (endpoint != null) {
            return UnmodifiableFuture.completedFuture(endpoint);
        }

        final ListeningFuture listeningFuture = new ListeningFuture(ctx, executor);
        endpointGroup.addListener(listeningFuture);

        // Try to select again because the EndpointGroup might have been updated
        // between selectNow() and addListener() above.
        endpoint = selectNow(ctx);
        if (endpoint != null) {
            endpointGroup.removeListener(listeningFuture);
            return UnmodifiableFuture.completedFuture(endpoint);
        }

        // Schedule the timeout task.
        final ScheduledFuture<?> timeoutFuture =
                executor.schedule(() -> listeningFuture.complete(null),
                                  timeoutMillis, TimeUnit.MILLISECONDS);
        listeningFuture.timeoutFuture = timeoutFuture;

        // Cancel the timeout task if listeningFuture is done already.
        // This guards against the following race condition:
        // 1) (Current thread) Timeout task is scheduled.
        // 2) ( Other thread ) listeningFuture is completed, but the timeout task is not cancelled
        // 3) (Current thread) timeoutFuture is assigned to listeningFuture.timeoutFuture, but it's too late.
        if (listeningFuture.isDone()) {
            timeoutFuture.cancel(false);
        }

        return listeningFuture;
    }

    private class ListeningFuture extends CompletableFuture<Endpoint> implements Consumer<List<Endpoint>> {
        private final ClientRequestContext ctx;
        private final Executor executor;
        @Nullable
        private volatile Endpoint selectedEndpoint;
        @Nullable
        private volatile ScheduledFuture<?> timeoutFuture;

        ListeningFuture(ClientRequestContext ctx, Executor executor) {
            this.ctx = ctx;
            this.executor = executor;
        }

        @Override
        public void accept(List<Endpoint> unused) {
            if (selectedEndpoint != null || isDone()) {
                return;
            }

            try {
                final Endpoint endpoint = selectNow(ctx);
                if (endpoint != null) {
                    cleanup();

                    // Complete with the selected endpoint.
                    selectedEndpoint = endpoint;
                    executor.execute(() -> super.complete(endpoint));
                }
            } catch (Throwable t) {
                completeExceptionally(t);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cleanup();
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean complete(Endpoint value) {
            cleanup();
            return super.complete(value);
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            cleanup();
            return super.completeExceptionally(ex);
        }

        private void cleanup() {
            group().removeListener(this);
            final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
            if (timeoutFuture != null) {
                this.timeoutFuture = null;
                timeoutFuture.cancel(false);
            }
        }
    }
}
