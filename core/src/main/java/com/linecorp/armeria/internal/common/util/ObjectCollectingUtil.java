/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A utility class which provides functions for collecting objects published from a {@link Publisher} or
 * {@link Stream}.
 */
public final class ObjectCollectingUtil {

    /**
     * The {@link Class} instance of {@code reactor.core.publisher.Mono} of
     * <a href="https://projectreactor.io/">Project Reactor</a>.
     */
    @Nullable
    private static final Class<?> MONO_CLASS;

    static {
        @Nullable Class<?> mono = null;
        try {
            mono = Class.forName("reactor.core.publisher.Mono",
                                 true, ObjectCollectingUtil.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // Do nothing.
        } finally {
            MONO_CLASS = mono;
        }
    }

    /**
     * Collects objects published from the specified {@link Stream}.
     *
     * @param stream publishes objects
     * @param executor executes the collecting job
     * @return a {@link CompletableFuture} which will complete when all published objects are collected
     */
    public static <T> CompletableFuture<List<T>> collectFrom(Stream<T> stream, Executor executor) {
        requireNonNull(stream, "stream");
        requireNonNull(executor, "executor");
        final CompletableFuture<List<T>> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(stream.collect(toImmutableList()));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Collects objects published from the specified {@link Publisher}.
     *
     * @param publisher publishes objects
     * @param ctx {@link ServiceRequestContext}
     * @return a {@link CompletableFuture} which will complete when all published objects are collected
     */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Object> collectFrom(Publisher<?> publisher, ServiceRequestContext ctx) {
        requireNonNull(publisher, "publisher");
        if (publisher instanceof StreamMessage) {
            final StreamMessage<?> stream = (StreamMessage<?>) publisher;
            ctx.whenRequestCancelling().thenAccept(cause -> stream.abort(cause));
            return (CompletableFuture<Object>) (CompletableFuture<?>) stream.collect();
        }
        final CompletableFuture<Object> future = new CompletableFuture<>();
        if (MONO_CLASS != null && MONO_CLASS.isAssignableFrom(publisher.getClass())) {
            publisher.subscribe(new CollectingSingleObjectSubscriber<>(future, ctx));
        } else {
            publisher.subscribe(new CollectingMultipleObjectsSubscriber<>(future, ctx));
        }
        return future;
    }

    /**
     * An abstract {@link Subscriber} implementation which collects objects published by a {@link Publisher}.
     */
    private abstract static class AbstractCollectingSubscriber<T> implements Subscriber<T> {
        private final CompletableFuture<Object> future;
        private final ServiceRequestContext ctx;
        private boolean onErrorCalled;

        AbstractCollectingSubscriber(CompletableFuture<Object> future, ServiceRequestContext ctx) {
            this.future = future;
            this.ctx = ctx;
        }

        @Override
        public void onSubscribe(Subscription s) {
            ctx.whenRequestCancelling().thenAccept(cause -> {
                if (!onErrorCalled) {
                    s.cancel();
                }
            });
            s.request(Integer.MAX_VALUE);
        }

        @Override
        public void onError(Throwable cause) {
            onErrorCalled = true;
            future.completeExceptionally(cause);
        }
    }

    private static class CollectingMultipleObjectsSubscriber<T> extends AbstractCollectingSubscriber<T> {
        @Nullable
        private ImmutableList.Builder<T> collector;

        CollectingMultipleObjectsSubscriber(CompletableFuture<Object> future, ServiceRequestContext ctx) {
            super(future, ctx);
        }

        @Override
        public void onNext(T o) {
            if (collector == null) {
                collector = new Builder<>();
            }
            collector.add(o);
        }

        @Override
        public void onComplete() {
            super.future.complete(collector != null ? collector.build() : ImmutableList.of());
        }
    }

    private static class CollectingSingleObjectSubscriber<T> extends AbstractCollectingSubscriber<T> {
        @Nullable
        private T result;

        CollectingSingleObjectSubscriber(CompletableFuture<Object> future, ServiceRequestContext ctx) {
            super(future, ctx);
        }

        @Override
        public void onNext(T o) {
            if (result == null) {
                result = o;
            } else {
                onError(new IllegalStateException("Only one element can be published: " + o));
            }
        }

        @Override
        public void onComplete() {
            super.future.complete(result);
        }
    }

    private ObjectCollectingUtil() {}
}
