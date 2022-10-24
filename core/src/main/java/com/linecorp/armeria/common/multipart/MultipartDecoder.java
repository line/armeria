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

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.common.stream.StreamDecoder;
import com.linecorp.armeria.common.stream.StreamDecoderInput;
import com.linecorp.armeria.common.stream.StreamDecoderOutput;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.internal.common.stream.AbortingSubscriber;
import com.linecorp.armeria.internal.common.stream.DecodedStreamMessage;
import com.linecorp.armeria.internal.common.stream.SubscriberUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class MultipartDecoder implements StreamMessage<BodyPart>, StreamDecoder<HttpData, BodyPart> {

    private static final AtomicReferenceFieldUpdater<MultipartDecoder, MultipartSubscriber>
            delegatedSubscriberUpdater = AtomicReferenceFieldUpdater.newUpdater(MultipartDecoder.class,
                                                                                MultipartSubscriber.class,
                                                                                "delegatedSubscriber");

    private final DecodedStreamMessage<HttpData, BodyPart> decoded;
    private final String boundary;

    @Nullable
    private MimeParser parser;

    @Nullable
    private volatile MultipartSubscriber delegatedSubscriber;

    // To track how many body part we need. Always keep DecodedHttpStreamMessage's demand less or equal than 1
    // This parameter is protected by the same executor of subscriber of this
    // and subscriber(this) of DecodedHttpStreamMessage.
    private long demandOfMultipart;

    MultipartDecoder(StreamMessage<? extends HttpData> upstream, String boundary, ByteBufAllocator alloc) {
        this.boundary = boundary;
        decoded = new DecodedStreamMessage<>(upstream, this, alloc);
    }

    @Override
    public ByteBuf toByteBuf(HttpData in) {
        return in.byteBuf();
    }

    @Override
    public void process(StreamDecoderInput in, StreamDecoderOutput<BodyPart> out) throws Exception {
        if (parser == null) {
            parser = new MimeParser(in, out, boundary, this);
        }
        parser.parse();
        // Do not catch the exception from parse() because processOnError is called eventually.
    }

    @Override
    public void processOnComplete(StreamDecoderInput in, StreamDecoderOutput<BodyPart> out) {
        if (parser != null) {
            try {
                parser.close();
            } catch (MimeParsingException e) {
                final MultipartSubscriber delegatedSubscriber = this.delegatedSubscriber;
                assert delegatedSubscriber != null : "processOnComplete is called after subscription";
                delegatedSubscriber.onError(e);
                // decoded is already complete so we don't need to call decoded.abort(e);
            }
        }
    }

    @Override
    public void processOnError(Throwable cause) {
        if (parser != null) {
            try {
                parser.close();
            } catch (MimeParsingException ignored) {
            }
        }
        final MultipartSubscriber delegatedSubscriber = this.delegatedSubscriber;
        assert delegatedSubscriber != null : "processOnError is called after subscription";
        delegatedSubscriber.onError(cause);
        // decoded.abort(e) is called by the thread who calls this method;
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

        final MultipartSubscriber multipartSubscriber = new MultipartSubscriber(subscriber, executor);
        if (!delegatedSubscriberUpdater.compareAndSet(this, null, multipartSubscriber)) {
            // Avoid calling method on late multipartSubscriber.
            // Because it's not static, so it will affect MultipartDecoder.
            SubscriberUtil.failLateSubscriber(executor, subscriber, delegatedSubscriber.subscriber);
            return;
        }
        decoded.subscribe(multipartSubscriber, executor, options);
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        if (delegatedSubscriber == null) {
            final MultipartSubscriber abortedSubscriber = new MultipartSubscriber(
                    AbortingSubscriber.get(cause), EventLoopGroups.directEventLoop());
            delegatedSubscriberUpdater.compareAndSet(this, null, abortedSubscriber);
        }
        decoded.abort(cause);
    }

    BodyPartPublisher onBodyPartBegin() {
        return new BodyPartPublisher();
    }

    void requestUpstreamForBodyPartData() {
        @Nullable
        final MultipartSubscriber delegatedSubscriber = this.delegatedSubscriber;
        if (delegatedSubscriber != null) {
            delegatedSubscriber.requestUpstreamForBodyPartData();
        }
    }

    final class BodyPartPublisher extends DefaultStreamMessage<HttpData> {
        @Override
        protected void onRequest(long n) {
            whenConsumed().thenRun(() -> {
                // It's safe to call upstream multiple times.
                // Because DecodedHttpStreamMessage#askUpstreamForElement only allows one upstream request
                // at the same time.
                // If the subscriber of BodyPartPublisher request multiple elements,
                // HttpMessageSubscriber#onNext calls MimeParser#parse and MimeParser will
                // ask for next element from upstream when parsing BODY.
                if (demand() > 0) {
                    requestUpstreamForBodyPartData();
                }
            });
        }
    }

    /**
     * For hiding Subscriber interface from MultipartDecoder.
     * Subscribing {@code DecodedHttpStreamMessage<BodyPart>} and delegating to external subscriber.
     */
    private final class MultipartSubscriber implements Subscriber<BodyPart>, Subscription {
        private final Subscriber<? super BodyPart> subscriber;
        private final EventExecutor executor;
        @Nullable
        private Subscription subscription;
        @Nullable
        private StreamMessage<? extends HttpData> currentExposedBodyPartPublisher;
        private boolean cancelled;

        private MultipartSubscriber(Subscriber<? super BodyPart> subscriber, EventExecutor executor) {
            this.subscriber = subscriber;
            this.executor = executor;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            subscriber.onSubscribe(this);
        }

        @Override
        public void onNext(BodyPart bodyPart) {
            if (demandOfMultipart != Long.MAX_VALUE) {
                demandOfMultipart--;
            }

            currentExposedBodyPartPublisher = bodyPart.content();
            // We only publish one bodyPart at a time.
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
            subscriber.onError(t);
            if (currentExposedBodyPartPublisher != null) {
                currentExposedBodyPartPublisher.abort(t);
            }
            cleanup();
        }

        @Override
        public void onComplete() {
            subscriber.onComplete();
            cleanup();
        }

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

        @SuppressWarnings("UnstableApiUsage")
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

        void requestUpstreamForBodyPartData() {
            if (executor.inEventLoop()) {
                requestUpstreamForBodyPartData0();
            } else {
                executor.execute(this::requestUpstreamForBodyPartData0);
            }
        }

        private void requestUpstreamForBodyPartData0() {
            // If it's closed(cancelled), let next body part request handle it.
            if (currentExposedBodyPartPublisher != null && currentExposedBodyPartPublisher.isOpen()) {
                decoded.askUpstreamForElement();
            }
        }

        private void cancel0() {
            if (cancelled) {
                return;
            }
            cancelled = true;

            // `this` is published after subscription assigned. So cancel on `this` always has subscription.
            assert subscription != null;
            subscription.cancel();

            if (currentExposedBodyPartPublisher != null) {
                currentExposedBodyPartPublisher.abort(CancelledSubscriptionException.get());
            }
            cleanup();
        }

        /**
         * Called by delegated MultipartSubscriber.
         * e.g.
         * 1. MultipartDecoder#abort -> MultipartSubscriber#onError -> MultipartSubscriber#cleanup ->
         *    MultipartDecoder#cleanup
         * 2. MultipartSubscriber#onComplete -> MultipartSubscriber#cleanup -> MultipartDecoder#cleanup
         */
        private void cleanup() {
            currentExposedBodyPartPublisher = null;
            delegatedSubscriber = null;
        }
    }
}
