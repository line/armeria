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
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.MoreObjects;
import com.google.common.primitives.Bytes;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.concurrent.EventExecutor;

final class DefaultMultipart implements Multipart {

    /**
     * The default boundary used for encoding multipart messages.
     */
    static final String DEFAULT_BOUNDARY = "!@==boundary==@!";

    private final String boundary;
    private final StreamMessage<? extends BodyPart> parts;

    DefaultMultipart(String boundary, StreamMessage<? extends BodyPart> parts) {
        this.boundary = boundary;
        this.parts = parts;
    }

    @Override
    public String boundary() {
        return boundary;
    }

    @SuppressWarnings("unchecked")
    @Override
    public StreamMessage<BodyPart> bodyParts() {
        return (StreamMessage<BodyPart>) parts;
    }

    @Override
    public CompletableFuture<AggregatedMultipart> aggregate() {
        return aggregate0(null);
    }

    @Override
    public CompletableFuture<AggregatedMultipart> aggregate(EventExecutor executor) {
        requireNonNull(executor, "executor");
        return aggregate0(executor);
    }

    private CompletableFuture<AggregatedMultipart> aggregate0(@Nullable EventExecutor executor) {
        final BodyPartAggregator aggregator = new BodyPartAggregator();
        if (executor == null) {
            parts.subscribe(aggregator);
        } else {
            parts.subscribe(aggregator, executor);
        }
        return UnmodifiableFuture.wrap(
                aggregator.completionFuture.thenApply(parts -> AggregatedMultipart.of(boundary, parts)));
    }

    @Override
    public boolean isOpen() {
        return parts.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return parts.isEmpty();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return parts.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber) {
        requireNonNull(subscriber, "subscriber");
        final MultipartEncoder encoder = new MultipartEncoder(boundary);
        parts.subscribe(encoder);
        encoder.subscribe(subscriber);
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        final MultipartEncoder encoder = new MultipartEncoder(boundary);
        parts.subscribe(encoder, executor);
        encoder.subscribe(subscriber, executor);
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");
        final MultipartEncoder encoder = new MultipartEncoder(boundary);
        parts.subscribe(encoder, executor, options);
        encoder.subscribe(subscriber, executor, options);
    }

    @Override
    public void abort() {
        parts.abort();
    }

    @Override
    public void abort(Throwable cause) {
        parts.abort(cause);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("boundary", boundary)
                          .add("parts", parts)
                          .toString();
    }

    private static final class BodyPartAggregator implements Subscriber<BodyPart> {

        private final CompletableFuture<List<AggregatedBodyPart>> completionFuture = new CompletableFuture<>();
        private final List<CompletableFuture<AggregatedBodyPart>> bodyPartFutures = new ArrayList<>();

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(BodyPart bodyPart) {
            requireNonNull(bodyPart, "bodyPart");
            final HttpDataAggregator aggregator = new HttpDataAggregator(bodyPart);
            bodyPart.content().subscribe(aggregator);
            bodyPartFutures.add(aggregator.completionFuture);
        }

        @Override
        public void onError(Throwable ex) {
            requireNonNull(ex, "ex");
            completionFuture.completeExceptionally(ex);
        }

        @Override
        public void onComplete() {
            CompletableFutures.allAsList(bodyPartFutures)
                              .whenComplete((parts, cause) -> {
                                  if (cause != null) {
                                      completionFuture.completeExceptionally(cause);
                                  } else {
                                      completionFuture.complete(parts);
                                  }
                              });
        }
    }

    /**
     * A subscriber of {@link HttpData} that accumulates bytes to a single {@link HttpData}.
     */
    private static final class HttpDataAggregator implements Subscriber<HttpData> {

        private final List<HttpData> dataList = new ArrayList<>();
        private final CompletableFuture<AggregatedBodyPart> completionFuture = new CompletableFuture<>();
        private final BodyPart bodyPart;

        HttpDataAggregator(BodyPart bodyPart) {
            this.bodyPart = bodyPart;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpData item) {
            requireNonNull(item, "item");
            dataList.add(item);
        }

        @Override
        public void onError(Throwable ex) {
            requireNonNull(ex, "ex");
            completionFuture.completeExceptionally(ex);
        }

        @Override
        public void onComplete() {
            final byte[][] arrays = new byte[dataList.size()][];
            for (int i = 0; i < arrays.length; i++) {
                arrays[i] = dataList.get(i).array();
            }
            completionFuture.complete(AggregatedBodyPart.of(bodyPart.headers(),
                                                            HttpData.wrap(Bytes.concat(arrays))));
        }
    }
}
