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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.HttpDecoderInput;
import com.linecorp.armeria.common.stream.HttpDecoderOutput;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.stream.DecodedHttpStreamMessage;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class MultipartDecoder implements StreamMessage<BodyPart>, HttpDecoder<BodyPart>, Subscriber<BodyPart> {

    private static final Logger logger = LoggerFactory.getLogger(MultipartDecoder.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<MultipartDecoder, Subscriber>
            subscriberUpdater = AtomicReferenceFieldUpdater.newUpdater(
            MultipartDecoder.class, Subscriber.class, "subscriber");

    private final DecodedHttpStreamMessage<BodyPart> decoded;
    private final String boundary;
    private final StreamMessage<? extends HttpData> upstream;

    @Nullable
    private MimeParser parser;

    // Below fields are modified by subscriber specified thread
    @Nullable
    private volatile Subscriber<? super BodyPart> subscriber;
    @Nullable
    private EventExecutor executor;
    @Nullable
    private BodyPartPublisher bodyPartPublisher;
    @Nullable
    private Subscription subscription;
    // To track how many body part we need. Always keep DecodedHttpStreamMessage's demand less or equal than 1
    private long demandOfMultipart;

    MultipartDecoder(StreamMessage<? extends HttpData> upstream, String boundary, ByteBufAllocator alloc) {
        this.upstream = upstream;
        this.boundary = boundary;
        decoded = new DecodedHttpStreamMessage<>(this.upstream, this, alloc);
    }

    @Override
    public void process(HttpDecoderInput in, HttpDecoderOutput<BodyPart> out) throws Exception {
        if (parser == null) {
            parser = new MimeParser(in, out, boundary, this);
        }
        parser.parse();
    }

    @Override
    public void processOnComplete(HttpDecoderOutput<BodyPart> out) {
        if (parser != null) {
            parser.close();
        }
    }

    @Override
    public void processOnError(Throwable cause) {
        if (parser != null) {
            try {
                parser.close();
            } catch (MimeParsingException ex) {
                logger.warn(ex.getMessage(), ex);
            }
        }
    }

    @Override
    public boolean isOpen() {
        return decoded.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return decoded.isEmpty();
    }

    @Override
    public long demand() {
        return demandOfMultipart;
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return decoded.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super BodyPart> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (!subscriberUpdater.compareAndSet(this, null, subscriber)) {
            if (executor.inEventLoop()) {
                abortLateSubscriber(subscriber);
            } else {
                executor.execute(() -> abortLateSubscriber(subscriber));
            }
            return;
        }

        if (executor.inEventLoop()) {
            subscribe0(subscriber, executor, options);
        } else {
            executor.execute(() -> subscribe0(subscriber, executor, options));
        }

    }

    private void subscribe0(Subscriber<? super BodyPart> subscriber, EventExecutor executor,
                            SubscriptionOption... options) {
        this.executor = executor;
        decoded.subscribe(this, executor, options);
    }

    private void abortLateSubscriber(Subscriber<? super BodyPart> subscriber) {
        subscriber.onSubscribe(NoopSubscription.get());
        subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
    }

    @Override
    public void abort() {
        assert executor != null;
        if (executor.inEventLoop()) {
            abort0();
        } else {
            executor.execute(this::abort0);
        }
    }

    private void abort0() {
        decoded.abort();
        if (bodyPartPublisher != null) {
            bodyPartPublisher.abort();
        }
    }

    @Override
    public void abort(Throwable cause) {
        assert executor != null;
        if (executor.inEventLoop()) {
            abort0(cause);
        } else {
            executor.execute(() -> abort0(cause));
        }
    }

    private void abort0(Throwable cause) {
        decoded.abort();
        if (bodyPartPublisher != null) {
            bodyPartPublisher.abort();
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        assert subscriber != null;
        subscriber.onSubscribe(new Subscription() {
            private boolean cancelled;

            @Override
            public void request(long n) {
                if (executor.inEventLoop()) {
                    request0(n);
                } else {
                    executor.execute(() -> request0(n));
                }
            }

            private void request0(long n) {
                final long oldDemand = demandOfMultipart;
                if (oldDemand >= Long.MAX_VALUE - n) {
                    demandOfMultipart = Long.MAX_VALUE;
                } else {
                    demandOfMultipart = oldDemand + n;
                }
                if (oldDemand == 0) {
                    // We want first body publisher
                    if (bodyPartPublisher == null) {
                        //This will trigger DecodedHttpStreamMessage's upstream.
                        subscription.request(1);
                    } else {
                        // Just wait bodyPart finish.
                    }
                }
            }

            @Override
            public void cancel() {
                if (executor.inEventLoop()) {
                    cancel0();
                } else {
                    executor.execute(this::cancel0);
                }
            }

            private void cancel0() {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                subscription.cancel();
                if (bodyPartPublisher != null) {
                    bodyPartPublisher.abort(CancelledSubscriptionException.get());
                }
            }
        });
    }

    @Override
    public void onNext(BodyPart bodyPart) {
        assert subscriber != null;
        demandOfMultipart--;
        subscriber.onNext(bodyPart);
    }

    @Override
    public void onError(Throwable t) {
        assert subscriber != null;
        subscriber.onError(t);
        if (bodyPartPublisher != null) {
            bodyPartPublisher.abort(t);
        }
    }

    @Override
    public void onComplete() {
        assert subscriber != null;
        subscriber.onComplete();
    }

    BodyPartPublisher onBodyPartBegin() {
        bodyPartPublisher = new BodyPartPublisher();
        // Next BodyPart must wait the current BodyPart close and fully consumed.
        bodyPartPublisher.whenComplete().handleAsync((unused, throwable) -> {
            bodyPartPublisher = null;
            if (throwable != null) {
                // Propagate to Multipart Subscriber
                // But now this comes after MultipartDecoder's onError due to MimeParser can't write any data
                // into BodyPartPublisher happens first
                abort(new BodyPartProcessException(throwable));
                return null;
            }
            if (demandOfMultipart > 0) {
                // BodyPart must be triggered after onSubscribe.
                assert subscription != null;
                // Trigger next body part
                subscription.request(1);
            }
            return null;
        }, executor);
        return bodyPartPublisher;
    }

    /**
     * MimeParser needs more data for body part.
     */
    public void requestBodyPartData() {
        assert executor != null;
        if (executor.inEventLoop()) {
            requestBodyPartData0();
        } else {
            executor.execute(this::requestBodyPartData0);
        }
    }

    private void requestBodyPartData0() {
        // No more upstream request and bodyPartPublisher still needs data
        if (bodyPartPublisher != null && bodyPartPublisher.needMoreData() && upstream.demand() == 0) {
            // TODO bodyPartPublisher.demand() is modified by bodypart subscriber's thread. Need to be changed.
            decoded.askUpstreamForElement();
        }
    }

    class BodyPartPublisher extends DefaultStreamMessage<HttpData> {
        private long remain = 0;

        @Override
        protected void onRequest(long n) {
            whenConsumed().thenRun(() -> {
                if (demand() > 0) {
                    requestBodyPartData();
                }
            });
        }

        @Override
        protected void onRemoval(HttpData obj) {
            remain--;
            super.onRemoval(obj);
        }

        @Override
        public boolean tryWrite(HttpData obj) {
            final boolean written = super.tryWrite(obj);
            if (written) {
                remain++;
            }
            return written;
        }

        /**
         * TODO
         * notifySubscriber(onRemoval) and tryWrite may run in different thread, so this count is not accurate.
         */
        boolean needMoreData() {
            return remain == 0 && demand() > 0;
        }
    }
}
