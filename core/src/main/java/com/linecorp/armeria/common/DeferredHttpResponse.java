/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.DeferredStreamMessage;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link HttpResponse} whose stream is published later by another {@link HttpResponse}. It is used when
 * an {@link HttpResponse} will not be instantiated early.
 */
final class DeferredHttpResponse extends DeferredStreamMessage<HttpObject> implements HttpResponse {

    /**
     * The {@link Class} instance of {@code reactor.core.publisher.MonoToCompletableFuture} of
     * <a href="https://projectreactor.io/">Project Reactor</a>.
     */
    @Nullable
    private static final Class<?> MONO_TO_FUTURE_CLASS;

    static {
        Class<?> monoToFuture = null;
        try {
            monoToFuture = Class.forName("reactor.core.publisher.MonoToCompletableFuture",
                                         true, DeferredHttpResponse.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // Do nothing.
        } finally {
            MONO_TO_FUTURE_CLASS = monoToFuture;
        }
    }

    @Nullable
    private final EventExecutor executor;

    DeferredHttpResponse() {
        executor = null;
    }

    DeferredHttpResponse(EventExecutor executor) {
        this.executor = executor;
    }

    void delegate(HttpResponse delegate) {
        super.delegate(delegate);
    }

    void delegateWhenComplete(CompletionStage<? extends HttpResponse> stage) {
        requireNonNull(stage, "stage");

        // Propagate exception to the upstream future.
        whenComplete().handle((unused, cause) -> {
            final CompletableFuture<? extends HttpResponse> future = stage.toCompletableFuture();
            if (cause != null && !future.isDone()) {
                if (MONO_TO_FUTURE_CLASS != null && MONO_TO_FUTURE_CLASS.isAssignableFrom(future.getClass())) {
                    // A workaround for 'MonoToCompletableFuture' not propagating cancellation to the upstream
                    // publisher when it completes exceptionally.
                    future.cancel(true);
                } else {
                    future.completeExceptionally(cause);
                }
            }
            return null;
        });
        stage.handle((delegate, thrown) -> {
            if (thrown != null) {
                if (!whenComplete().isDone()) {
                    close(Exceptions.peel(thrown));
                } else {
                    return null;
                }
            } else if (delegate == null) {
                close(new NullPointerException("delegate stage produced a null response: " + stage));
            } else {
                delegate(delegate);
            }
            return null;
        });
    }

    @Override
    public EventExecutor defaultSubscriberExecutor() {
        if (executor != null) {
            return executor;
        }
        return super.defaultSubscriberExecutor();
    }
}
