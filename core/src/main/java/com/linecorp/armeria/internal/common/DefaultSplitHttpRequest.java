package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SplitHttpRequest;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;

public class DefaultSplitHttpRequest implements StreamMessage<HttpData>, SplitHttpRequest {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSplitHttpRequest.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<BodySubscriber, Subscriber> downstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(BodySubscriber.class, Subscriber.class, "downstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultSplitHttpRequest, HeadersFuture>
            trailersFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(DefaultSplitHttpRequest.class, HeadersFuture.class, "trailersFuture");

    private static final HttpHeaders EMPTY_TRAILERS = HttpHeaders.of();

    private final BodySubscriber bodySubscriber = new BodySubscriber();
    private final HttpRequest request;
    private final EventExecutor upstreamExecutor;

    @Nullable
    private volatile HeadersFuture<HttpHeaders> trailersFuture;

    private volatile boolean wroteAny;

    public DefaultSplitHttpRequest(HttpRequest request, EventExecutor executor) {
        this.request = requireNonNull(request, "request");
        upstreamExecutor = requireNonNull(executor, "executor");

        request.subscribe(bodySubscriber, upstreamExecutor, SubscriptionOption.values());
    }

    @Override
    public RequestHeaders headers() {
        return request.headers();
    }

    @Override
    public StreamMessage<HttpData> body() {
        return this;
    }

    @Override
    public CompletableFuture<HttpHeaders> trailers() {
        HeadersFuture<HttpHeaders> trailersFuture = this.trailersFuture;
        if (trailersFuture != null) {
            return trailersFuture;
        }

        trailersFuture = new HeadersFuture<>();
        if (trailersFutureUpdater.compareAndSet(this, null, trailersFuture)) {
            return trailersFuture;
        } else {
            return this.trailersFuture;
        }
    }

    @Override
    public boolean isOpen() {
        return request.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return !isOpen() && !wroteAny;
    }

    @Override
    public long demand() {
        return request.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return request.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (!downstreamUpdater.compareAndSet(bodySubscriber, null, subscriber)) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
            return;
        }

        if (executor.inEventLoop()) {
            bodySubscriber.initDownstream(subscriber, executor, options);
        } else {
            executor.execute(() -> bodySubscriber.initDownstream(subscriber, executor, options));
        }
    }

    @Override
    public void abort() {
        request.abort();
    }

    @Override
    public void abort(Throwable cause) {
        request.abort(cause);
    }

    private final class BodySubscriber implements Subscriber<HttpObject>, Subscription {

        private boolean completing;

        @Nullable
        private volatile Subscription upstream;

        @Nullable
        volatile Subscriber<? super HttpData> downstream;

        @Nullable
        private volatile EventExecutor executor;

        @Nullable
        private volatile Throwable cause;

        private volatile boolean cancelCalled;
        private volatile boolean notifyCancellation;
        private boolean usePooledObject;

        private void initDownstream(Subscriber<? super HttpData> downstream, EventExecutor executor,
                                    SubscriptionOption... options) {
            assert executor.inEventLoop();

            this.executor = executor;
            for (SubscriptionOption option : options) {
                if (option == SubscriptionOption.NOTIFY_CANCELLATION) {
                    notifyCancellation = true;
                } else if (option == SubscriptionOption.WITH_POOLED_OBJECTS) {
                    usePooledObject = true;
                }
            }

            try {
                downstream.onSubscribe(this);
                final Throwable cause = this.cause;
                if (cause != null) {
                    onError0(cause, downstream);
                } else if (completing) {
                    onComplete0(downstream);
                }
            } catch (Throwable t) {
                throwIfFatal(t);
                logger.warn("Subscriber should not throw an exception. subscriber: {}", downstream, t);
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (upstream != null) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            if (cancelCalled) {
                subscription.cancel();
            }
        }

        @Override
        public void onNext(HttpObject httpObject) {
            if (httpObject instanceof HttpHeaders) {
                final HttpHeaders trailers = (HttpHeaders) httpObject;
                completeTrailers(trailers);
                return;
            }

            final Subscriber<? super HttpData> downstream = this.downstream;
            assert downstream != null;
            assert httpObject instanceof HttpData;

            final EventExecutor executor = this.executor;
            if (executor.inEventLoop()) {
                onNext0((HttpData) httpObject);
            } else {
                executor.execute(() -> onNext0((HttpData) httpObject));
            }
        }

        private void onNext0(HttpData httpData) {
            wroteAny = true;
            if (!usePooledObject) {
                httpData = PooledObjects.copyAndClose(httpData);
            }
            downstream.onNext(httpData);
        }

        private void completeTrailers(HttpHeaders trailers) {
            HeadersFuture<HttpHeaders> trailersFuture = DefaultSplitHttpRequest.this.trailersFuture;
            if (trailersFuture != null) {
                trailersFuture.doComplete(trailers);
                return;
            }

            trailersFuture = new HeadersFuture<>();
            if (trailersFutureUpdater.compareAndSet(DefaultSplitHttpRequest.this, null, trailersFuture)) {
                trailersFuture.doComplete(trailers);
            } else {
                DefaultSplitHttpRequest.this.trailersFuture.doComplete(trailers);
            }
        }

        @Override
        public void onError(Throwable cause) {
            completeTrailers(EMPTY_TRAILERS);
            final EventExecutor executor = this.executor;
            final Subscriber<? super HttpData> downstream = this.downstream;
            if (executor == null || downstream == null) {
                this.cause = cause;
                return;
            }

            if (executor.inEventLoop()) {
                onError0(cause, downstream);
            } else {
                executor.execute(() -> onError0(cause, downstream));
            }
        }

        private void onError0(Throwable cause, Subscriber<? super HttpData> downstream) {
            downstream.onError(cause);
            this.downstream = NoopSubscriber.get();
        }

        @Override
        public void onComplete() {
            completeTrailers(EMPTY_TRAILERS);
            final EventExecutor executor = this.executor;
            final Subscriber<? super HttpData> downstream = this.downstream;
            if (executor == null || downstream == null) {
                completing = true;
                return;
            }

            if (executor.inEventLoop()) {
                onComplete0(downstream);
            } else {
                executor.execute(() -> onComplete0(downstream));
            }
        }

        private void onComplete0(Subscriber<? super HttpData> downstream) {
            downstream.onComplete();
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                request.abort(new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
                return;
            }
            if (upstreamExecutor.inEventLoop()) {
                request0(n);
            } else {
                upstreamExecutor.execute(() -> request0(n));
            }
        }

        private void request0(long n) {
            final Subscription upstream = this.upstream;
            upstream.request(n);
        }

        @Override
        public void cancel() {
            if (cancelCalled) {
                return;
            }
            cancelCalled = true;
            if (!notifyCancellation) {
                downstream = NoopSubscriber.get();
            }
            completeTrailers(EMPTY_TRAILERS);
            final Subscription upstream = this.upstream;
            if (upstream != null) {
                upstream.cancel();
            }
        }
    }

    private static final class HeadersFuture<T> extends UnmodifiableFuture<T> {

        @Override
        protected void doComplete(@Nullable T value) {
            super.doComplete(value);
        }

        @Override
        protected void doCompleteExceptionally(Throwable cause) {
            super.doCompleteExceptionally(cause);
        }
    }
}
