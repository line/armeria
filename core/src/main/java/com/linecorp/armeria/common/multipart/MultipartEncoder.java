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

import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;

import io.netty.util.AsciiString;

/**
 * Reactive processor that encodes a stream of {@link BodyPart} into an HTTP payload.
 */
class MultipartEncoder implements Processor<BodyPart, HttpData> {

    // Forked from https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/media/multipart/src/main/java/io/helidon/media/multipart/MultiPartEncoder.java

    private static final AtomicReferenceFieldUpdater<MultipartEncoder, Subscription> upstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    MultipartEncoder.class, Subscription.class, "upstream");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<MultipartEncoder, Subscriber> downstreamUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    MultipartEncoder.class, Subscriber.class, "downstream");

    private final String boundary;
    private final CompletableFuture<BufferedEmittingPublisher<Publisher<HttpData>>> initFuture;

    @Nullable
    private BufferedEmittingPublisher<Publisher<HttpData>> emitter;

    // Updated only via upstreamUpdater
    @Nullable
    private volatile Subscription upstream;
    // Updated only via downstreamUpdater
    @Nullable
    private volatile Subscriber<? super HttpData> downstream;

    MultipartEncoder(String boundary) {
        requireNonNull(boundary, "boundary");
        this.boundary = boundary;
        initFuture = new CompletableFuture<>();
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber) {
        requireNonNull(subscriber, "subscriber");
        if (emitter != null || !downstreamUpdater.compareAndSet(this, null, subscriber)) {
            subscriber.onSubscribe(SubscriptionHelper.CANCELED);
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

    private void deferredInit() {
        final Subscription upstream = this.upstream;
        final Subscriber<? super HttpData> downstream = this.downstream;
        if (upstream != null && downstream != null) {
            emitter = new BufferedEmittingPublisher<>();
            // relay request to upstream, already reduced by flatmap
            emitter.onRequest((r, t) -> upstream.request(r));
            Multi.from(emitter)
                 .flatMap(Function.identity())
                 .onCompleteResume(HttpData.ofUtf8("--" + boundary + "--"))
                 .subscribe(downstream);
            initFuture.complete(emitter);
            downstreamUpdater.set(this, null);
        }
    }

    @Override
    public void onNext(BodyPart bodyPart) {
        emitter.emit(createBodyPartPublisher(bodyPart));
    }

    @Override
    public void onError(Throwable throwable) {
        requireNonNull(throwable, "throwable");
        initFuture.whenComplete((e, t) -> e.fail(throwable));
    }

    @Override
    public void onComplete() {
        initFuture.whenComplete((e, t) -> e.complete());
    }

    private Publisher<HttpData> createBodyPartPublisher(BodyPart bodyPart) {
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
        return Multi.concat(Multi.concat(// Part prefix
                                         Multi.singleton(HttpData.ofUtf8(sb.toString())),
                                         // Part body
                                         bodyPart.content()),
                            // Part postfix
                            Multi.singleton(HttpData.ofUtf8("\r\n")));
    }
}
