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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.MoreObjects;
import com.google.common.primitives.Bytes;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

final class DefaultMultiPart implements MultiPart {

    /**
     * The default boundary used for encoding multipart messages.
     */
    static final String DEFAULT_BOUNDARY = "!@==boundary==@!";

    private final String boundary;
    private final MultiPartEncoder encoder;
    private final Publisher<BodyPart> parts;

    DefaultMultiPart(String boundary, Publisher<BodyPart> parts) {
        this.boundary = boundary;
        encoder = new MultiPartEncoder(boundary);
        this.parts = parts;
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> s) {
        parts.subscribe(encoder);
        encoder.subscribe(s);
    }

    @Override
    public String boundary() {
        return boundary;
    }

    @Override
    public Publisher<BodyPart> bodyParts() {
        return parts;
    }

    @Override
    public CompletableFuture<AggregatedMultiPart> aggregate() {
        final BodyPartAggregator aggregator = new BodyPartAggregator();
        parts.subscribe(aggregator);
        return UnmodifiableFuture.wrap(
                aggregator.future.thenApply(parts -> new AggregatedMultiPart(boundary, parts)));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("boundary", boundary)
                          .add("encoder", encoder)
                          .add("parts", parts)
                          .toString();
    }

    private static final class BodyPartAggregator implements Subscriber<BodyPart> {

        private final List<AggregatedBodyPart> bodyParts = new ArrayList<>();
        private final CompletableFuture<List<AggregatedBodyPart>> future = new CompletableFuture<>();

        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(BodyPart bodyPart) {
            final HttpDataAggregator aggregator = new HttpDataAggregator();
            bodyPart.content().subscribe(aggregator);
            aggregator.future.thenAccept(data -> {
                bodyParts.add(new AggregatedBodyPart(bodyPart.headers(), data));
            });
        }

        @Override
        public void onError(Throwable t) {
            future.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            future.complete(bodyParts);
        }
    }

    /**
     * A subscriber of {@link HttpData} that accumulates bytes to a single {@link HttpData}.
     */
    private static final class HttpDataAggregator implements Subscriber<HttpData> {

        private final List<HttpData> dataList = new ArrayList<>();
        private final CompletableFuture<HttpData> future = new CompletableFuture<>();

        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpData item) {
            dataList.add(item);
        }

        @Override
        public void onError(Throwable ex) {
            future.completeExceptionally(ex);
        }

        @Override
        public void onComplete() {
            final byte[][] arrays = new byte[dataList.size()][];
            for (int i = 0; i < arrays.length; i++) {
                arrays[i] = dataList.get(i).array();
            }
            future.complete(HttpData.wrap(Bytes.concat(arrays)));
        }
    }
}
