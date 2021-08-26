/*
 * Copyright 2021 LINE Corporation
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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link HttpResponse} that delegates the {@link HttpResponse} completed with
 * {@link CompletableHttpResponse#complete(HttpResponse)}.
 */
@UnstableApi
public final class CompletableHttpResponse extends EventLoopCheckingFuture<HttpResponse>
        implements HttpResponse, Subscription {

    private static final AtomicIntegerFieldUpdater<CompletableHttpResponse> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(CompletableHttpResponse.class, "subscribed");

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
                                         true, CompletableHttpResponse.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // Do nothing.
        } finally {
            MONO_TO_FUTURE_CLASS = monoToFuture;
        }
    }

    static CompletableHttpResponse of(CompletionStage<? extends HttpResponse> stage,
                                      @Nullable EventExecutor executor) {
        requireNonNull(stage, "stage");

        final CompletableHttpResponse response = new CompletableHttpResponse(executor);
        // Propagate exception to the upstream future.
        response.handle((unused, cause) -> {
            final CompletableFuture<? extends HttpResponse> future = stage.toCompletableFuture();
            if (cause != null && !future.isDone()) {
                if (MONO_TO_FUTURE_CLASS != null && MONO_TO_FUTURE_CLASS.isAssignableFrom(future.getClass())) {
                    // A workaround for 'MonoToCompletableFuture' not propagating cancellation to the upstream
                    // publisher when it completes exceptionally.
                    future.cancel(true);
                } else {
                    future.completeExceptionally(Exceptions.peel(cause));
                }
            }
            return null;
        });

        stage.handle((upstream, thrown) -> {
            if (thrown != null) {
                if (!response.isDone()) {
                    response.completeExceptionally(Exceptions.peel(thrown));
                } else {
                    return null;
                }
            } else if (upstream == null) {
                response.completeExceptionally(
                        new NullPointerException("upstream stage produced a null response: " + stage));
            } else {
                response.complete(upstream);
            }
            return null;
        });
        return response;
    }

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();
    @Nullable
    private Subscription upstreamSubscription;
    private long pendingDemand;

    @Nullable
    private volatile HttpResponse upstream;
    @Nullable
    private volatile EventExecutor executor;
    // Updated via subscribedUpdater
    private volatile int subscribed;

    CompletableHttpResponse(@Nullable EventExecutor executor) {
        if (executor != null) {
            this.executor = executor;
        }
    }

    @Override
    public boolean complete(HttpResponse upstream) {
        requireNonNull(upstream, "upstream");

        final boolean success = super.complete(upstream);
        if (success) {
            this.upstream = upstream;
        } else {
            upstream.abort(new IllegalStateException("upstream set already"));
        }
        return success;
    }

    /**
     * Throws an {@link UnsupportedOperationException}.
     * If completed already, cannot be overridden by the specifed {@link HttpResponse}.
     *
     * @deprecated  Use {@link #complete(HttpResponse)} instead.
     */
    @Deprecated
    @Override
    public void obtrudeValue(@Nullable HttpResponse upstream) {
        throw new UnsupportedOperationException();
    }

    /**
     * Throws an {@link UnsupportedOperationException}.
     * If completed already, cannot be overridden by the specified {@link Throwable}.
     *
     * @deprecated Use {@link #completeExceptionally(Throwable)} instead.
     */
    @Deprecated
    @Override
    public void obtrudeException(Throwable ex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOpen() {
        final HttpResponse upstream = this.upstream;
        if (upstream != null) {
            return upstream.isOpen();
        }
        return !isDone();
    }

    @Override
    public boolean isEmpty() {
        final HttpResponse upstream = this.upstream;
        if (upstream != null) {
            return upstream.isEmpty();
        }
        return isDone();
    }

    @Override
    public long demand() {
        final HttpResponse upstream = this.upstream;
        if (upstream != null) {
            return upstream.demand();
        }
        return pendingDemand;
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");
        final EventExecutor eventExecutor = firstNonNull(this.executor, executor);
        if (executor.inEventLoop()) {
            subscribe0(subscriber, eventExecutor, options);
        } else {
            eventExecutor.execute(() -> subscribe0(subscriber, eventExecutor, options));
        }
    }

    private void subscribe0(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                           SubscriptionOption... options) {
        if (!subscribedUpdater.compareAndSet(this, 0, 1)) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
        } else {
            // Executor should be set before subscriber.onSubscribe(this) is called.
            if (this.executor == null) {
                this.executor = executor;
            }
            subscriber.onSubscribe(this);

            final HttpResponse upstream = this.upstream;
            final ForwardingSubscriber forwardingSubscriber = new ForwardingSubscriber(subscriber);
            if (upstream != null) {
                // upstream is already completed.
                subscribeToUpstream(upstream, null, forwardingSubscriber, executor, options);
                return;
            }

            // Subscribe to upstream when completed.
            handle((response, cause) -> {
                if (executor.inEventLoop()) {
                    subscribeToUpstream(response, cause, forwardingSubscriber, executor, options);
                } else {
                    executor.execute(() -> subscribeToUpstream(response, cause, forwardingSubscriber,
                                                               executor, options));
                }
                return null;
            });
        }
    }

    private void subscribeToUpstream(@Nullable HttpResponse upstream, @Nullable Throwable cause,
                                     Subscriber<? super HttpObject> subscriber,
                                     EventExecutor executor, SubscriptionOption[] options) {
        if (cause != null) {
            abortSubscriber(subscriber, cause);
            return;
        }

        assert upstream != null;
        upstream.subscribe(subscriber, executor, options);
        // Propagate the result of response.whenComplete() to completionFuture.
        upstream.whenComplete().handle((unused, cause0) -> {
            if (cause0 != null) {
                completionFuture.completeExceptionally(cause0);
            } else {
                completionFuture.complete(null);
            }
            return null;
        });
    }

    private void abortSubscriber(Subscriber<? super HttpObject> subscriber, Throwable cause) {
        subscriber.onError(cause);
        completionFuture.completeExceptionally(cause);
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");

        final HttpResponse upstream = this.upstream;
        if (upstream != null) {
            upstream.abort(cause);
        } else {
            final boolean success = completeExceptionally(cause);
            if (!success && !isCompletedExceptionally()) {
                // An HttpResponse has just been completed before exceptionally completing.
                thenAccept(response -> response.abort(cause));
            }
        }
    }

    @Override
    public void request(long n) {
        final EventExecutor executor = this.executor;
        assert executor != null;
        if (executor.inEventLoop()) {
            request0(n);
        } else {
            executor.execute(() -> request0(n));
        }
    }

    private void request0(long n) {
        if (upstreamSubscription != null) {
            upstreamSubscription.request(n);
        } else {
            pendingDemand = LongMath.saturatedAdd(pendingDemand, n);
        }
    }

    @Override
    public void cancel() {
        abort(CancelledSubscriptionException.get());
    }

    private final class ForwardingSubscriber implements Subscriber<HttpObject> {

        private final Subscriber<? super HttpObject> downstreamSubscriber;

        ForwardingSubscriber(Subscriber<? super HttpObject> downstreamSubscriber) {
            this.downstreamSubscriber = downstreamSubscriber;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            upstreamSubscription = subscription;
            if (pendingDemand > 0) {
                subscription.request(pendingDemand);
                pendingDemand = 0;
            }
        }

        @Override
        public void onNext(HttpObject t) {
            downstreamSubscriber.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            downstreamSubscriber.onError(t);
        }

        @Override
        public void onComplete() {
            downstreamSubscriber.onComplete();
        }
    }
}
