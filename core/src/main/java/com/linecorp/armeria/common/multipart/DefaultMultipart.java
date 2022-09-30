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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.MoreObjects;
import com.google.common.io.BaseEncoding;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ByteStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class DefaultMultipart implements Multipart, StreamMessage<HttpData> {

    private static final BaseEncoding base64 = BaseEncoding.base64().omitPadding();
    private static final String BOUNDARY_PARAMETER = "boundary";
    private static final MediaType DEFAULT_MULTIPART_TYPE = MediaType.MULTIPART_FORM_DATA;

    /**
     * Returns a random boundary used for encoding multipart messages.
     */
    static String randomBoundary() {
        final byte[] bytes = new byte[12];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "ArmeriaBoundary" + base64.encode(bytes);
    }

    private final String boundary;
    private final StreamMessage<BodyPart> parts;

    DefaultMultipart(String boundary, StreamMessage<? extends BodyPart> parts) {
        this.boundary = boundary;
        //noinspection unchecked
        this.parts = (StreamMessage<BodyPart>) parts;
    }

    @Override
    public String boundary() {
        return boundary;
    }

    @Override
    public StreamMessage<BodyPart> bodyParts() {
        return parts;
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");
        final MultipartEncoder encoder = new MultipartEncoder(parts, boundary);
        encoder.subscribe(subscriber, executor, options);
    }

    @Override
    public CompletableFuture<AggregatedMultipart> aggregate() {
        return aggregate(defaultSubscriberExecutor());
    }

    @Override
    public CompletableFuture<AggregatedMultipart> aggregate(EventExecutor executor) {
        requireNonNull(executor, "executor");
        return aggregate0(executor, null);
    }

    @Override
    public CompletableFuture<AggregatedMultipart> aggregateWithPooledObjects(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        return aggregateWithPooledObjects(defaultSubscriberExecutor(), alloc);
    }

    @Override
    public CompletableFuture<AggregatedMultipart> aggregateWithPooledObjects(EventExecutor executor,
                                                                             ByteBufAllocator alloc) {
        requireNonNull(executor, "executor");
        requireNonNull(alloc, "alloc");
        return aggregate0(executor, alloc);
    }

    private CompletableFuture<AggregatedMultipart> aggregate0(@Nullable EventExecutor executor,
                                                              @Nullable ByteBufAllocator alloc) {
        final BodyPartAggregator aggregator = new BodyPartAggregator(alloc);
        if (executor == null) {
            parts.subscribe(aggregator);
        } else {
            parts.subscribe(aggregator, executor);
        }
        return UnmodifiableFuture.wrap(
                aggregator.completionFuture.thenApply(parts -> AggregatedMultipart.of(boundary, parts)));
    }

    @Override
    public HttpRequest toHttpRequest(RequestHeaders requestHeaders) {
        final RequestHeaders requestHeadersWithBoundary = injectBoundary(boundary, requestHeaders);
        return HttpRequest.of(requestHeadersWithBoundary, this);
    }

    @Override
    public HttpRequest toHttpRequest(String path) {
        requireNonNull(path, "path");
        final MediaType contentType = DEFAULT_MULTIPART_TYPE.withParameter(BOUNDARY_PARAMETER, boundary);
        final RequestHeaders requestHeaders = RequestHeaders.builder(HttpMethod.POST, path)
                                                            .contentType(contentType)
                                                            .build();
        return HttpRequest.of(requestHeaders, this);
    }

    @Override
    public HttpResponse toHttpResponse(ResponseHeaders responseHeaders) {
        final ResponseHeaders responseHeadersWithBoundary = injectBoundary(boundary, responseHeaders);
        return HttpResponse.of(responseHeadersWithBoundary, this);
    }

    @Override
    public HttpResponse toHttpResponse(HttpStatus status) {
        requireNonNull(status, "status");
        final MediaType contentType = DEFAULT_MULTIPART_TYPE.withParameter(BOUNDARY_PARAMETER, boundary);
        final ResponseHeaders responseHeaders = ResponseHeaders.builder(status)
                                                               .contentType(contentType)
                                                               .build();
        return HttpResponse.of(responseHeaders, this);
    }

    @Override
    public ByteStreamMessage toStreamMessage() {
        return ByteStreamMessage.of(this);
    }

    @Override
    public boolean isOpen() {
        return parts.isOpen();
    }

    @Override
    public boolean isEmpty() {
        // This is always false even parts.isEmpty() == true.
        // It's because isEmpty() is called after this multipart is converted into a StreamMessage and the
        // StreamMessage produces at least a closing boundary.
        return false;
    }

    @Override
    public long demand() {
        return parts.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return parts.whenComplete();
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

    @SuppressWarnings("unchecked")
    private static <T extends HttpHeaders> T injectBoundary(String boundary, T headers) {
        requireNonNull(headers, "headers");
        @Nullable
        MediaType contentType = headers.contentType();
        if (contentType != null) {
            checkArgument(contentType.isMultipart(),
                          "Content-Type: %s (expected: multipart content type)", contentType);
            contentType = contentType.withParameter(BOUNDARY_PARAMETER, boundary);
        } else {
            contentType = DEFAULT_MULTIPART_TYPE.withParameter(BOUNDARY_PARAMETER, boundary);
        }
        final MediaType contentTypeWithBoundary = contentType;
        return (T) headers.withMutations(builder -> builder.contentType(contentTypeWithBoundary));
    }

    private static final class BodyPartAggregator implements Subscriber<BodyPart> {

        private final CompletableFuture<List<AggregatedBodyPart>> completionFuture = new CompletableFuture<>();
        private final List<CompletableFuture<AggregatedBodyPart>> bodyPartFutures = new ArrayList<>();

        @Nullable
        private final ByteBufAllocator alloc;

        BodyPartAggregator(@Nullable ByteBufAllocator alloc) {
            this.alloc = alloc;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(BodyPart bodyPart) {
            requireNonNull(bodyPart, "bodyPart");
            if (alloc != null) {
                bodyPartFutures.add(bodyPart.aggregateWithPooledObjects(alloc));
            } else {
                bodyPartFutures.add(bodyPart.aggregate());
            }
        }

        @Override
        public void onError(Throwable ex) {
            requireNonNull(ex, "ex");
            completionFuture.completeExceptionally(ex);
        }

        @Override
        public void onComplete() {
            CompletableFutures.allAsList(bodyPartFutures)
                              .handle((parts, cause) -> {
                                  if (cause != null) {
                                      completionFuture.completeExceptionally(cause);
                                  } else {
                                      completionFuture.complete(parts);
                                  }
                                  return null;
                              });
        }
    }
}
