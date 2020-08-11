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

import java.util.ArrayDeque;
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
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.buffer.ByteBuf;

/**
 * Reactive processor that decodes HTTP payload as a stream of {@link BodyPart}.
 */
class MultipartDecoder implements Processor<HttpData, BodyPart> {

    // Forked from https://github.com/oracle/helidon/blob/a9363a3d226a3154e2fb99abe230239758504436/media/multipart/src/main/java/io/helidon/media/multipart/MultiPartDecoder.java

    private static final AtomicReferenceFieldUpdater<MultipartDecoder, Subscription> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    MultipartDecoder.class, Subscription.class, "upstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<MultipartDecoder, Subscriber> downstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    MultipartDecoder.class, Subscriber.class, "downstream");

    private static final AtomicIntegerFieldUpdater<MultipartDecoder> initializedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(MultipartDecoder.class, "initialized");

    private final CompletableFuture<StreamMessage<BodyPart>> initFuture;
    private final ArrayDeque<BodyPart> bodyParts;
    private final MimeParser parser;
    private final ParserEventProcessor parserEventProcessor;

    @Nullable
    private ListenableStreamMessage<BodyPart> emitter;
    @Nullable
    private BodyPartBuilder bodyPartBuilder;
    @Nullable
    private HttpHeadersBuilder bodyPartHeaderBuilder;
    @Nullable
    private ListenableStreamMessage<HttpData> bodyPartPublisher;

    @Nullable
    private ListenableStreamMessage<HttpData> lastDeliveredContent;

    // Updated only via upstreamUpdater
    @Nullable
    private volatile Subscription upstream;
    // Updated via downstreamUpdater
    @Nullable
    private volatile Subscriber<? super BodyPart> downstream;
    // Updated via initializedUpdater
    private volatile int initialized;

    MultipartDecoder(String boundary) {
        requireNonNull(boundary, "boundary");
        parserEventProcessor = new ParserEventProcessor();
        parser = new MimeParser(boundary, parserEventProcessor);
        initFuture = new CompletableFuture<>();
        bodyParts = new ArrayDeque<>();
    }

    @Override
    public void subscribe(Subscriber<? super BodyPart> subscriber) {
        requireNonNull(subscriber, "subscriber");
        if (!downstreamUpdater.compareAndSet(this, null, subscriber)) {
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
            emitter.abort(ex);
            chunk.close();
            upstream.cancel();
            return;
        }

        // submit parsed parts
        while (!bodyParts.isEmpty()) {
            if (!emitter.isOpen()) {
                return;
            }
            emitter.write(bodyParts.poll());
        }

        // complete the parts publisher
        if (parserEventProcessor.isCompleted()) {
            emitter.close();
            // parts are delivered sequentially
            // we potentially drop the last part if not requested
        }

        // request more data to detect the next part
        // if not in the middle of a part content
        // or if the part content subscriber needs more
        if (upstream != SubscriptionHelper.CANCELLED &&
            emitter.demand() > 0 &&
            parserEventProcessor.isDataRequired() &&
            (!parserEventProcessor.isContentDataRequired() || bodyPartPublisher.demand() > 0)) {
            upstream.request(1);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        cleanupUnsubscribed();
        requireNonNull(throwable, "throwable");
        initFuture.thenAccept(emitter -> emitter.abort(throwable));
    }

    @Override
    public void onComplete() {
        initFuture.whenComplete((e, t) -> {
            cleanupUnsubscribed();
            if (upstream != SubscriptionHelper.CANCELLED) {
                upstream = SubscriptionHelper.CANCELLED;
                try {
                    parser.close();
                } catch (MimeParsingException ex) {
                    emitter.abort(ex);
                }
            }
        });
    }

    private void deferredInit() {
        final Subscriber<? super BodyPart> downstream = this.downstream;
        if (upstream != null && downstream != null) {
            if (initializedUpdater.compareAndSet(this, 0, 1)) {
                emitter = new ListenableStreamMessage<>(this::onRequest, upstream::cancel, this::onEmitPart);
                emitter.subscribe(downstream);
                initFuture.complete(emitter);
                this.downstream = null;
            }
        }
    }

    private void onRequest(long unused) {
        // require more raw chunks to decode if the decoding has not
        // yet started or if more data is required to make progress
        if (!parserEventProcessor.isStarted() || parserEventProcessor.isDataRequired()) {
            upstream.request(1);
        }
    }

    private void cleanupUnsubscribed() {
        if (lastDeliveredContent != null && !lastDeliveredContent.isSubscribed()) {
            lastDeliveredContent.abort();
        }
    }

    private void onEmitPart(BodyPart part) {
        final ListenableStreamMessage<HttpData> deliveredBodyPart = lastDeliveredContent;

        final StreamMessage<HttpData> content = part.content();
        if (content instanceof ListenableStreamMessage) {
            lastDeliveredContent = (ListenableStreamMessage<HttpData>) content;
        }

        if (deliveredBodyPart != null && !deliveredBodyPart.isSubscribed()) {
            deliveredBodyPart.abort();
        }
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
                    bodyPartPublisher = new ListenableStreamMessage<>(MultipartDecoder.this::onRequest, null,
                                                                      null);
                    bodyPartHeaderBuilder = HttpHeaders.builder();
                    bodyPartBuilder = BodyPart.builder();
                    break;
                case HEADER:
                    final MimeParser.HeaderEvent headerEvent = event.asHeaderEvent();
                    bodyPartHeaderBuilder.add(headerEvent.name(), headerEvent.value());
                    break;
                case END_HEADERS:
                    bodyParts.offer(createPart());
                    break;
                case CONTENT:
                    final ByteBuf content = event.asContentEvent().content();
                    final HttpData copied = HttpData.copyOf(content);
                    final ByteBuf unwrap = content.unwrap();
                    if (unwrap.isReadable()) {
                        unwrap.discardSomeReadBytes();
                    }
                    bodyPartPublisher.write(copied);
                    break;
                case END_PART:
                    bodyPartPublisher.close();
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
