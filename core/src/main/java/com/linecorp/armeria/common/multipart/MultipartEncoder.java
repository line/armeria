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
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.util.AsciiString;
import io.netty.util.concurrent.EventExecutor;

/**
 * Reactive processor that encodes a stream of {@link BodyPart}s into an HTTP payload.
 */
final class MultipartEncoder implements StreamMessage<HttpData> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<MultipartEncoder, CompletableFuture>
            completionFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(MultipartEncoder.class, CompletableFuture.class, "completionFuture");

    private static final AtomicIntegerFieldUpdater<MultipartEncoder> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(MultipartEncoder.class, "subscribed");

    private static final HttpData CRLF = HttpData.ofUtf8("\r\n");

    private final String boundary;

    private final StreamMessage<BodyPart> publisher;

    private volatile int subscribed;

    // 'closeCause' will be written before 'closed' and read after 'closed'
    @Nullable
    private Throwable closeCause;
    private volatile boolean closed;

    @Nullable
    private volatile CompletableFuture<Void> completionFuture;

    @Nullable
    private volatile DefaultStreamMessage<StreamMessage<HttpData>> emitter;

    MultipartEncoder(StreamMessage<BodyPart> publisher, String boundary) {
        requireNonNull(boundary, "boundary");
        requireNonNull(publisher, "publisher");

        this.boundary = boundary;
        this.publisher = publisher;
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        final StreamMessage<StreamMessage<HttpData>> emitter = this.emitter;
        if (emitter != null) {
            return emitter.whenComplete();
        }

        final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        if (completionFutureUpdater.compareAndSet(this, null, completionFuture)) {
            return completionFuture;
        } else {
            return this.completionFuture;
        }
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (!subscribedUpdater.compareAndSet(this, 0, 1)) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("Only one Subscriber allowed"));
            return;
        }

        publisher.subscribe(new BodyPartSubscriber(subscriber, executor, options), executor, options);
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        if (closed) {
            return;
        }

        closeCause = cause;
        closed = true;

        final StreamMessage<StreamMessage<HttpData>> emitter = this.emitter;
        if (emitter != null) {
            emitter.abort(cause);
        }
    }

    @Override
    public boolean isOpen() {
        final StreamMessage<StreamMessage<HttpData>> emitter = this.emitter;
        return emitter == null || emitter.isOpen();
    }

    @Override
    public boolean isEmpty() {
        final StreamMessage<StreamMessage<HttpData>> emitter = this.emitter;
        return emitter == null || emitter.isEmpty();
    }

    @Override
    public long demand() {
        final StreamMessage<StreamMessage<HttpData>> emitter = this.emitter;
        if (emitter == null) {
            return 0;
        }
        return emitter.demand();
    }

    private static DefaultStreamMessage<StreamMessage<HttpData>> newEmitter(Subscription upstream) {
        final DefaultStreamMessage<StreamMessage<HttpData>> emitter =
                new DefaultStreamMessage<StreamMessage<HttpData>>() {
                    @Override
                    protected void onRequest(long n, long oldDemand) {
                        // A BodyPart is converted to a StreamMessage<HttpData> one to one.
                        upstream.request(n);
                    }
                };
        emitter.whenComplete().handle((unused, cause) -> {
            if (cause instanceof CancelledSubscriptionException) {
                upstream.cancel();
            }
            return null;
        });
        return emitter;
    }

    private StreamMessage<HttpData> createBodyPartPublisher(BodyPart bodyPart) {
        // start boundary
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder sb = tempThreadLocals.stringBuilder();
            sb.append("--").append(boundary).append("\r\n");

            // headers lines
            for (Entry<AsciiString, String> header : bodyPart.headers()) {
                final AsciiString headerName = header.getKey();
                final String headerValue = header.getValue();
                sb.append(headerName)
                  .append(':')
                  .append(headerValue)
                  .append("\r\n");
            }

            // end of headers empty line
            sb.append("\r\n");
            return StreamMessage.concat(
                    // Part prefix
                    StreamMessage.of(HttpData.ofUtf8(sb.toString())),
                    // Part body
                    bodyPart.content(),
                    // Part postfix
                    StreamMessage.of(CRLF));
        }
    }

    private final class BodyPartSubscriber implements Subscriber<BodyPart> {

        @Nullable
        private Subscriber<? super HttpData> downstream;
        private final EventExecutor executor;
        private final SubscriptionOption[] options;

        private boolean subscribed;

        private BodyPartSubscriber(Subscriber<? super HttpData> downstream, EventExecutor executor,
                                   SubscriptionOption[] options) {
            this.downstream = downstream;
            this.executor = executor;
            this.options = options;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (subscribed) {
                subscription.cancel();
                return;
            }

            assert downstream != null;

            subscribed = true;
            final DefaultStreamMessage<StreamMessage<HttpData>> newEmitter = newEmitter(subscription);

            // The 'emitter' should be set before reading 'closed' flag.
            // It guarantees that the emitter.abort() or downstream.onError() is always called with
            // 'closedCause'
            emitter = newEmitter;

            if (closed) {
                downstream.onError(closeCause);
                newEmitter.abort(CancelledSubscriptionException.get());
                return;
            }

            final CompletableFuture<Void> completionFuture = MultipartEncoder.this.completionFuture;
            if (completionFuture != null) {
                completeAsync(newEmitter.whenComplete(), completionFuture);
            } else {
                if (!completionFutureUpdater
                        .compareAndSet(MultipartEncoder.this, null, newEmitter.whenComplete())) {
                    completeAsync(newEmitter.whenComplete(), MultipartEncoder.this.completionFuture);
                }
            }

            StreamMessage.concat(StreamMessage.concat(newEmitter),
                                 StreamMessage.of(HttpData.ofUtf8("--" + boundary + "--")))
                         .subscribe(downstream, executor, options);

            downstream = null;
        }

        private void completeAsync(CompletableFuture<Void> first, CompletableFuture<Void> second) {
            first.handle((unused, cause) -> {
                if (cause != null) {
                    second.completeExceptionally(cause);
                } else {
                    second.complete(null);
                }
                return null;
            });
        }

        @Override
        public void onNext(BodyPart bodyPart) {
            requireNonNull(bodyPart, "bodyPart");
            emitter.write(createBodyPartPublisher(bodyPart));
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");

            if (closed) {
                return;
            }

            closed = true;
            emitter.abort(cause);
        }

        @Override
        public void onComplete() {
            if (closed) {
                return;
            }

            closed = true;
            emitter.close();
        }
    }
}
