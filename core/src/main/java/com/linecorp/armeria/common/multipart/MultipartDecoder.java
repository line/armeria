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
/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

/**
 * Reactive processor that decodes HTTP payload as a stream of {@link BodyPart}.
 */
class MultipartDecoder implements Processor<HttpData, BodyPart> {

    // Forked from https://github.com/oracle/helidon/blob/a9363a3d226a3154e2fb99abe230239758504436/media/multipart/src/main/java/io/helidon/media/multipart/MultiPartDecoder.java

    private static final AtomicReferenceFieldUpdater<MultipartDecoder, Subscription> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    MultipartDecoder.class, Subscription.class, "upstream");

    private static final AtomicIntegerFieldUpdater<MultipartDecoder> initializedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(MultipartDecoder.class, "initialized");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<MultipartDecoder, Subscriber> downstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    MultipartDecoder.class, Subscriber.class, "downstream");

    private final CompletableFuture<BufferedEmittingPublisher<BodyPart>> initFuture;
    private final LinkedList<BodyPart> bodyParts;
    private final MimeParser parser;
    private final ParserEventProcessor parserEventProcessor;

    @Nullable
    private BufferedEmittingPublisher<BodyPart> emitter;
    @Nullable
    private BodyPartBuilder bodyPartBuilder;
    @Nullable
    private HttpHeadersBuilder bodyPartHeaderBuilder;
    @Nullable
    private BufferedEmittingPublisher<HttpData> bodyPartPublisher;

    // Updated only via upstreamUpdater
    @Nullable
    private volatile Subscription upstream;
    // Set a non-null value via downstreamUpdater or directly set null
    @Nullable
    private volatile Subscriber<? super BodyPart> downstream;
    // Updated via initializedUpdater
    private volatile int initialized;

    MultipartDecoder(String boundary) {
        requireNonNull(boundary, "boundary");
        parserEventProcessor = new ParserEventProcessor();
        parser = new MimeParser(boundary, parserEventProcessor);
        initFuture = new CompletableFuture<>();
        bodyParts = new LinkedList<>();
    }

    @Override
    public void subscribe(Subscriber<? super BodyPart> subscriber) {
        requireNonNull(subscriber, "subscriber");
        if (emitter != null || !downstreamUpdater.compareAndSet(this, null, subscriber)) {
            subscriber.onSubscribe(SubscriptionHelper.CANCELLED);
            subscriber.onError(new IllegalStateException("Only one Subscriber allowed"));
            return;
        }
        deferredInit();
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        if (!upstreamUpdater.compareAndSet(this, null, subscription)) {
            subscription.cancel();
            throw new IllegalStateException("Subscription already set.");
        }
        deferredInit();
    }

    @Override
    public void onNext(HttpData chunk) {
        try {
            parser.offer(chunk.byteBuf());
            parser.parse();
        } catch (MimeParsingException ex) {
            emitter.fail(ex);
            PooledObjects.close(chunk);
            upstream.cancel();
            return;
        }

        // submit parsed parts
        while (!bodyParts.isEmpty()) {
            if (emitter.isCancelled()) {
                return;
            }
            emitter.emit(bodyParts.poll());
        }

        // complete the parts publisher
        if (parserEventProcessor.isCompleted()) {
            emitter.complete();
            // parts are delivered sequentially
            // we potentially drop the last part if not requested
            emitter.clearBuffer(MultipartDecoder::drainPart);
        }

        // request more data to detect the next part
        // if not in the middle of a part content
        // or if the part content subscriber needs more
        if (upstream != SubscriptionHelper.CANCELLED &&
            emitter.hasRequests() &&
            parserEventProcessor.isDataRequired() &&
            (!parserEventProcessor.isContentDataRequired() || bodyPartPublisher.hasRequests())) {
            upstream.request(1);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        requireNonNull(throwable, "throwable");
        initFuture.thenAccept(emitter -> emitter.fail(throwable));
    }

    @Override
    public void onComplete() {
        initFuture.whenComplete((e, t) -> {
            if (upstream != SubscriptionHelper.CANCELLED) {
                upstream = SubscriptionHelper.CANCELLED;
                try {
                    parser.close();
                } catch (MimeParsingException ex) {
                    emitter.fail(ex);
                }
            }
        });
    }

    private void deferredInit() {
        final Subscriber<? super BodyPart> downstream = this.downstream;
        if (upstream != null && downstream != null) {
            if (initializedUpdater.compareAndSet(this, 0, 1)) {
                emitter = new BufferedEmittingPublisher<>(this::onPartRequest, MultipartDecoder::drainPart);
                emitter.subscribe(downstream);
                initFuture.complete(emitter);
                this.downstream = null;
            }
        }
    }

    private void onPartRequest(long unused) {
        // require more raw chunks to decode if the decoding has not
        // yet started or if more data is required to make progress
        if (!parserEventProcessor.isStarted() || parserEventProcessor.isDataRequired()) {
            upstream.request(1);
        }
    }

    private static void drainPart(BodyPart part) {
        part.content().subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                requireNonNull(subscription, "subscription");
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData item) {
                ReferenceCountUtil.release(item);
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
    }

    private BodyPart createPart() {
        final HttpHeaders headers = bodyPartHeaderBuilder.build();

        return bodyPartBuilder
                .headers(headers)
                .content(bodyPartPublisher)
                .build();
    }

    private final class ParserEventProcessor implements MimeParser.EventProcessor {

        @Nullable
        private MimeParser.ParserEvent lastEvent;

        @Override
        public void process(MimeParser.ParserEvent event) {
            final MimeParser.EventType eventType = event.type();
            switch (eventType) {
                case START_PART:
                    bodyPartPublisher = new BufferedEmittingPublisher<>();
                    bodyPartHeaderBuilder = HttpHeaders.builder();
                    bodyPartBuilder = BodyPart.builder();
                    break;
                case HEADER:
                    final MimeParser.HeaderEvent headerEvent = event.asHeaderEvent();
                    bodyPartHeaderBuilder.add(headerEvent.name(), headerEvent.value());
                    break;
                case END_HEADERS:
                    bodyParts.add(createPart());
                    break;
                case CONTENT:
                    final ByteBuf content = event.asContentEvent().content();
                    final HttpData copied = HttpData.copyOf(content);
                    final ByteBuf unwrap = content.unwrap();
                    if (unwrap.isReadable()) {
                        unwrap.discardSomeReadBytes();
                    }
                    bodyPartPublisher.emit(copied);
                    break;
                case END_PART:
                    bodyPartPublisher.complete();
                    bodyPartPublisher = null;
                    bodyPartHeaderBuilder = null;
                    bodyPartBuilder = null;
                    break;
                default:
                    // nothing to do
            }
            lastEvent = event;
        }

        /**
         * Indicates if the parser has received any data.
         *
         * @return {@code true} if the parser has been offered data, {@code false} otherwise
         */
        boolean isStarted() {
            return lastEvent != null;
        }

        /**
         * Indicates if the parser has reached the end of the message.
         *
         * @return {@code true} if completed, {@code false} otherwise
         */
        boolean isCompleted() {
            return lastEvent.type() == MimeParser.EventType.END_MESSAGE;
        }

        /**
         * Indicates if the parser requires more data to make progress.
         *
         * @return {@code true} if more data is required, {@code false} otherwise
         */
        boolean isDataRequired() {
            return lastEvent.type() == MimeParser.EventType.DATA_REQUIRED;
        }

        /**
         * Indicates if more content data is required.
         *
         * @return {@code true} if more content data is required, {@code false} otherwise
         */
        boolean isContentDataRequired() {
            return isDataRequired() && lastEvent.asDataRequiredEvent().isContent();
        }
    }
}
