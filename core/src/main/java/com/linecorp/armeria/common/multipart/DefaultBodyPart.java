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

import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessages;
import com.linecorp.armeria.internal.common.HttpObjectAggregator;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class DefaultBodyPart implements BodyPart {

    private final HttpHeaders headers;
    private final StreamMessage<? extends HttpData> content;

    DefaultBodyPart(HttpHeaders headers, StreamMessage<? extends HttpData> content) {
        this.headers = headers;
        this.content = content;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @SuppressWarnings("unchecked")
    @Override
    public StreamMessage<HttpData> content() {
        return (StreamMessage<HttpData>) content;
    }

    @Override
    public CompletableFuture<Void> writeTo(Path path, EventExecutor eventExecutor,
                                           ExecutorService blockingTaskExecutor,
                                           OpenOption... options) {
        return StreamMessages.writeTo(content, path, eventExecutor, blockingTaskExecutor, options);
    }

    @Override
    public CompletableFuture<AggregatedBodyPart> aggregate() {
        return aggregate0(content().defaultSubscriberExecutor(), null);
    }

    @Override
    public CompletableFuture<AggregatedBodyPart> aggregate(EventExecutor executor) {
        requireNonNull(executor, "executor");
        return aggregate0(executor, null);
    }

    @Override
    public CompletableFuture<AggregatedBodyPart> aggregateWithPooledObjects(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        return aggregate0(content().defaultSubscriberExecutor(), alloc);
    }

    @Override
    public CompletableFuture<AggregatedBodyPart> aggregateWithPooledObjects(EventExecutor executor,
                                                                            ByteBufAllocator alloc) {
        requireNonNull(executor, "executor");
        requireNonNull(alloc, "alloc");
        return aggregate0(executor, alloc);
    }

    private CompletableFuture<AggregatedBodyPart> aggregate0(EventExecutor executor,
                                                             @Nullable ByteBufAllocator alloc) {
        final CompletableFuture<AggregatedBodyPart> future = new CompletableFuture<>();
        content().subscribe(new ContentAggregator(headers, future, alloc), executor);
        return future;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("headers", headers)
                          .add("content", content)
                          .toString();
    }

    /**
     * Aggregates a {@link BodyPart#content()}.
     */
    private static final class ContentAggregator extends HttpObjectAggregator<AggregatedBodyPart> {

        private final HttpHeaders headers;

        ContentAggregator(HttpHeaders headers, CompletableFuture<AggregatedBodyPart> future,
                          @Nullable ByteBufAllocator alloc) {
            super(future, alloc);
            this.headers = headers;
        }

        @Override
        protected void onHeaders(HttpHeaders headers) {}

        @Override
        protected AggregatedBodyPart onSuccess(HttpData content) {
            return AggregatedBodyPart.of(headers, content);
        }

        @Override
        protected void onFailure() {}
    }
}
