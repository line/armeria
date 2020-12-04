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

import static com.linecorp.armeria.common.multipart.StreamMessages.EMPTY_OPTIONS;
import static java.util.Objects.requireNonNull;

import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.AsciiString;
import io.netty.util.concurrent.EventExecutor;

/**
 * Reactive processor that encodes a stream of {@link BodyPart} into an HTTP payload.
 */
class MultipartEncoder implements Processor<BodyPart, HttpData>, StreamMessage<HttpData> {

    // Forked from https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/media/multipart/src/main/java/io/helidon/media/multipart/MultiPartEncoder.java

    private static final AtomicReferenceFieldUpdater<MultipartEncoder, Subscription> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    MultipartEncoder.class, Subscription.class, "upstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<MultipartEncoder, Subscriber> downstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    MultipartEncoder.class, Subscriber.class, "downstream");

    private static final AtomicIntegerFieldUpdater<MultipartEncoder> initializedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(MultipartEncoder.class, "initialized");

    private static final HttpData CRLF = HttpData.ofUtf8("\r\n");

    private final String boundary;
    private final CompletableFuture<ListenableStreamMessage<StreamMessage<HttpData>>> initFuture;

    @Nullable
    private ListenableStreamMessage<StreamMessage<HttpData>> emitter;

    // Updated only via upstreamUpdater
    @Nullable
    private volatile Subscription upstream;
    // Set a non-null value via downstreamUpdater or directly set null
    @Nullable
    private volatile Subscriber<? super HttpData> downstream;
    // Updated via initializedUpdater
    private volatile int initialized;

    // Read 'executor' and 'options' only after 'initialized' is written.
    @Nullable
    private EventExecutor executor;
    @Nullable
    private SubscriptionOption[] options;

    MultipartEncoder(String boundary) {
        requireNonNull(boundary, "boundary");
        this.boundary = boundary;
        initFuture = new CompletableFuture<>();
    }

    @Override
    public boolean isOpen() {
        return emitter == null || emitter.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return emitter == null || emitter.isEmpty();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return initFuture.thenCompose(e -> e.whenComplete());
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, EMPTY_OPTIONS);
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        this.executor = requireNonNull(executor, "executor");
        this.options = requireNonNull(options, "options");

        if (emitter != null || !downstreamUpdater.compareAndSet(this, null, subscriber)) {
            subscriber.onSubscribe(CancelledSubscription.CANCELLED);
            subscriber.onError(new IllegalStateException("Only one Subscriber allowed"));
            return;
        }
        deferredInit();
    }

    @Override
    public void abort() {
        initFuture.handle((e, t) -> {
            e.abort();
            return null;
        });
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        initFuture.handle((e, t) -> {
            e.abort(cause);
            return null;
        });
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        if (!upstreamUpdater.compareAndSet(this, null, subscription)) {
            subscription.cancel();
        }
        deferredInit();
    }

    private void deferredInit() {
        final Subscription upstream = this.upstream;
        final Subscriber<? super HttpData> downstream = this.downstream;
        if (upstream != null && downstream != null) {
            // relay request to upstream, already reduced by flatmap
            if (initializedUpdater.compareAndSet(this, 0, 1)) {
                emitter = new ListenableStreamMessage<>(upstream::request, upstream::cancel);
                StreamMessages.concat(StreamMessages.concat(emitter),
                                      StreamMessage.of(HttpData.ofUtf8("--" + boundary + "--")))
                              .subscribe(downstream, executor, options);

                initFuture.complete(emitter);
                this.downstream = null;
            }
        }
    }

    @Override
    public void onNext(BodyPart bodyPart) {
        requireNonNull(bodyPart, "bodyPart");
        emitter.write(createBodyPartPublisher(bodyPart));
    }

    @Override
    public void onError(Throwable throwable) {
        requireNonNull(throwable, "throwable");
        initFuture.handle((e, t) -> {
            e.abort(throwable);
            return null;
        });
    }

    @Override
    public void onComplete() {
        initFuture.handle((e, t) -> {
            e.close();
            return null;
        });
    }

    private StreamMessage<HttpData> createBodyPartPublisher(BodyPart bodyPart) {
        // start boundary
        final StringBuilder sb = new StringBuilder("--").append(boundary).append("\r\n");

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
        return StreamMessages.concat(
                // Part prefix
                StreamMessage.of(HttpData.ofUtf8(sb.toString())),
                // Part body
                bodyPart.content(),
                // Part postfix
                StreamMessage.of(CRLF));
    }
}
