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
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.HttpDecoderInput;
import com.linecorp.armeria.common.stream.HttpDecoderOutput;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.stream.DecodedHttpStreamMessage;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class MultipartDecoder implements StreamMessage<BodyPart>, HttpDecoder<BodyPart> {

    private static final Logger logger = LoggerFactory.getLogger(MultipartDecoder.class);

    private static final AtomicIntegerFieldUpdater<MultipartDecoder>
            subscriberUpdater = AtomicIntegerFieldUpdater.newUpdater(MultipartDecoder.class, "subscribed");

    private final DecodedHttpStreamMessage<BodyPart> decoded;
    private final String boundary;

    @Nullable
    private MimeParser parser;

    private volatile int subscribed;

    // Below fields are modified by subscriber specified thread
    @Nullable
    private Subscriber<? super BodyPart> subscriber;
    @Nullable
    private EventExecutor executor;
    @Nullable
    private StreamMessage<? extends HttpData> currentExposedBodyPartPublisher;
    @Nullable
    private Subscription subscription;
    // To track how many body part we need. Always keep DecodedHttpStreamMessage's demand less or equal than 1
    // This parameter is protected by the same executor of subscriber of this
    // and subscriber(this) of DecodedHttpStreamMessage.
    private long demandOfMultipart;

    MultipartDecoder(StreamMessage<? extends HttpData> upstream, String boundary, ByteBufAllocator alloc) {
        this.boundary = boundary;
        decoded = new DecodedHttpStreamMessage<>(upstream, this, alloc);
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

        if (!subscriberUpdater.compareAndSet(this, 0, 1)) {
            if (executor.inEventLoop()) {
                abortLateSubscriber(subscriber);
            } else {
                executor.execute(() -> abortLateSubscriber(subscriber));
            }
            return;
        }

        this.executor = executor;
        this.subscriber = subscriber;
        decoded.subscribe(new MultipartSubscriber(), executor, options);
    }

    private void abortLateSubscriber(Subscriber<? super BodyPart> subscriber) {
        subscriber.onSubscribe(NoopSubscription.get());
        subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
    }

    @Override
    public void abort() {
        if (subscriberUpdater.compareAndSet(this, 0, 1)) {
            cleanup();
            return;
        }
        assert executor != null;
        if (executor.inEventLoop()) {
            abort0(AbortedStreamException.get());
        } else {
            executor.execute(() -> abort0(AbortedStreamException.get()));
        }
    }

    @Override
    public void abort(Throwable cause) {
        if (subscriberUpdater.compareAndSet(this, 0, 1)) {
            cleanup();
            return;
        }
        assert executor != null;
        if (executor.inEventLoop()) {
            abort0(cause);
        } else {
            executor.execute(() -> abort0(cause));
        }
    }

    private void abort0(Throwable cause) {
        decoded.abort(cause);
        if (currentExposedBodyPartPublisher != null) {
            currentExposedBodyPartPublisher.abort(cause);
        }
        if (subscriber != null) {
            subscriber.onError(cause);
        }
        cleanup();
    }

    private void cleanup() {
        if (currentExposedBodyPartPublisher != null) {
            currentExposedBodyPartPublisher = null;
        }
        subscriber = NoopSubscriber.get();
    }

    BodyPartPublisher onBodyPartBegin() {
        return new BodyPartPublisher();
    }

    public void requestUpstreamForBodyPartData() {
        assert executor != null;
        if (executor.inEventLoop()) {
            requestUpstreamForBodyPartData0();
        } else {
            executor.execute(this::requestUpstreamForBodyPartData0);
        }
    }

    private void requestUpstreamForBodyPartData0() {
        // No more upstream request and bodyPartPublisher still needs data
        // If it's closed(cancelled), let next body part request handle it.
        if (currentExposedBodyPartPublisher != null && currentExposedBodyPartPublisher.isOpen()) {
            decoded.askUpstreamForElement();
        }
    }

    class BodyPartPublisher extends DefaultStreamMessage<HttpData> {
        @Override
        protected void onRequest(long n) {
            // TODO: Should be safe?
            // Because whenConsumed will run in the same thread(called by onRequest) after looping the existing
            // queue.(onRequest & event notification in whenConsumed will run in the same executor specified
            // at subscribe)
            // So if there is no change, it means there is no buffered data processed.
            whenConsumed().thenRun(() -> {
                // There isn't any buffered data, or it's not enough
                if (demand() > 0) {
                    requestUpstreamForBodyPartData();
                }
            });
        }
    }

    /**
     * For hiding Subscriber interface from MultipartDecoder.
     * Subscribing {@code DecodedHttpStreamMessage<BodyPart>}
     */
    private class MultipartSubscriber implements Subscriber<BodyPart> {
        @Override
        public void onSubscribe(Subscription subscription) {
            MultipartDecoder.this.subscription = subscription;
            assert subscriber != null;
            subscriber.onSubscribe(new Subscription() {
                private boolean cancelled;

                @Override
                public void request(long n) {
                    if (n <= 0) {
                        final IllegalArgumentException exception = new IllegalArgumentException(
                                "Expecting only positive requests for parts");
                        abort(exception);
                        return;
                    }
                    if (executor.inEventLoop()) {
                        request0(n);
                    } else {
                        executor.execute(() -> request0(n));
                    }
                }

                private void request0(long n) {
                    final long oldDemand = demandOfMultipart;
                    demandOfMultipart = LongMath.saturatedAdd(oldDemand, n);
                    if (oldDemand == 0) {
                        // We want first body publisher
                        if (currentExposedBodyPartPublisher == null) {
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
                    if (currentExposedBodyPartPublisher != null) {
                        currentExposedBodyPartPublisher.abort(CancelledSubscriptionException.get());
                    }
                    cleanup();
                }
            });
        }

        @Override
        public void onNext(BodyPart bodyPart) {
            assert subscriber != null;

            if (demandOfMultipart != Long.MAX_VALUE) {
                demandOfMultipart--;
            }

            currentExposedBodyPartPublisher = bodyPart.content();
            // We only publish one bodyPart one time.
            // Next BodyPart must wait the current BodyPart close and fully consumed.
            currentExposedBodyPartPublisher.whenComplete().handle((unused, throwable) -> {
                if (executor.inEventLoop()) {
                    onNext0();
                } else {
                    executor.execute(this::onNext0);
                }
                return null;
            });
            subscriber.onNext(bodyPart);
        }

        private void onNext0() {
            currentExposedBodyPartPublisher = null;
            if (demandOfMultipart > 0 && !isComplete()) {
                // BodyPart must be triggered after onSubscribe.
                assert subscription != null;
                // Trigger next body part
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable t) {
            assert subscriber != null;
            subscriber.onError(t);
            if (currentExposedBodyPartPublisher != null) {
                currentExposedBodyPartPublisher.abort(t);
            }
            cleanup();
        }

        @Override
        public void onComplete() {
            assert subscriber != null;
            subscriber.onComplete();
            cleanup();
        }
    }
}
