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
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A body part entity.
 */
public interface BodyPart {

    /**
     * Returns a new UTF-8-encoded {@link BodyPart} using the specified {@link ContentDisposition} and
     * {@link CharSequence}.
     */
    static BodyPart of(ContentDisposition contentDisposition, CharSequence content) {
        requireNonNull(content, "content");
        return of(contentDisposition, HttpData.ofUtf8(content));
    }

    /**
     * Returns a new UTF-8-encoded {@link BodyPart} using the specified {@link ContentDisposition},
     * {@link MediaType} and {@link CharSequence}.
     */
    static BodyPart of(ContentDisposition contentDisposition, MediaType contentType, CharSequence content) {
        requireNonNull(content, "content");
        return of(contentDisposition, contentType, HttpData.ofUtf8(content));
    }

    /**
     * Returns a new UTF-8-encoded {@link BodyPart} using the specified {@link HttpHeaders} and
     * {@link CharSequence}.
     */
    static BodyPart of(HttpHeaders headers, CharSequence content) {
        requireNonNull(content, "content");
        return of(headers, HttpData.ofUtf8(content));
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link ContentDisposition} and
     * {@link HttpData}.
     */
    static BodyPart of(ContentDisposition contentDisposition, HttpData content) {
        requireNonNull(content, "content");
        return of(contentDisposition, StreamMessage.of(content));
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link ContentDisposition}, {@link MediaType} and
     * {@link HttpData}.
     */
    static BodyPart of(ContentDisposition contentDisposition, MediaType contentType, HttpData content) {
        requireNonNull(content, "content");
        return of(contentDisposition, contentType, StreamMessage.of(content));
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link HttpHeaders} and
     * {@code bytes}.
     */
    static BodyPart of(HttpHeaders headers, HttpData content) {
        requireNonNull(content, "content");
        return of(headers, StreamMessage.of(content));
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link ContentDisposition} and
     * {@link Publisher} of {@link HttpData}.
     */
    static BodyPart of(ContentDisposition contentDisposition, Publisher<? extends HttpData> publisher) {
        final HttpHeaders headers = HttpHeaders.builder().contentDisposition(contentDisposition).build();
        return of(headers, publisher);
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link ContentDisposition}, {@link MediaType} and
     * {@link Publisher} of {@link HttpData}.
     */
    static BodyPart of(ContentDisposition contentDisposition, MediaType contentType,
                       Publisher<? extends HttpData> publisher) {
        requireNonNull(contentDisposition, "contentDisposition");
        requireNonNull(contentType, "contentType");
        requireNonNull(publisher, "publisher");
        final HttpHeaders headers = HttpHeaders.builder()
                                               .contentDisposition(contentDisposition)
                                               .contentType(contentType)
                                               .build();
        return of(headers, publisher);
    }

    /**
     * Returns a new {@link BodyPart} using the specified {@link HttpHeaders} and
     * {@link Publisher} of {@link HttpData}.
     */
    static BodyPart of(HttpHeaders headers, Publisher<? extends HttpData> publisher) {
        return builder().headers(headers).content(publisher).build();
    }

    /**
     * Returns a new {@link BodyPartBuilder}.
     */
    static BodyPartBuilder builder() {
        return new BodyPartBuilder();
    }

    /**
     * Returns HTTP part headers.
     */
    HttpHeaders headers();

    /**
     * Returns the reactive representation of the part content.
     */
    @CheckReturnValue
    StreamMessage<HttpData> content();

    /**
     * Write this {@link BodyPart} to the given {@link Path} with {@link OpenOption}.
     * If the {@link OpenOption} is not specified, defaults to {@link StandardOpenOption#CREATE},
     * {@link StandardOpenOption#TRUNCATE_EXISTING} and {@link StandardOpenOption#WRITE}.
     *
     * @param path the {@link Path} to write to
     * @param eventExecutor the {@link EventExecutor} to subscribe to this given publisher
     * @param blockingTaskExecutor the {@link ExecutorService} to which blocking tasks are submitted to handle
     *                             file I/O events and write operations
     * @param options the {@link OpenOption} specifying how the file is opened
     * @return a {@link CompletableFuture} to handle asynchronous result
     */
    CompletableFuture<Void> writeTo(Path path, EventExecutor eventExecutor,
                                    ExecutorService blockingTaskExecutor, OpenOption... options);

    /**
     * Aggregates this {@link BodyPart}. The returned {@link CompletableFuture} will be notified when
     * the {@link BodyPart} is received fully.
     */
    CompletableFuture<AggregatedBodyPart> aggregate();

    /**
     * Aggregates this {@link BodyPart}. The returned {@link CompletableFuture} will be notified when
     * the {@link BodyPart} is received fully.
     */
    CompletableFuture<AggregatedBodyPart> aggregate(EventExecutor executor);

    /**
     * (Advanced users only) Aggregates this {@link BodyPart}. The returned {@link CompletableFuture}
     * will be notified when the {@link BodyPart} is received fully.
     * {@link AggregatedBodyPart#content()} will return a pooled object, and the caller must ensure
     * to release it. If you don't know what this means, use {@link #aggregate()}.
     */
    CompletableFuture<AggregatedBodyPart> aggregateWithPooledObjects(ByteBufAllocator alloc);

    /**
     * (Advanced users only) Aggregates this {@link BodyPart}. The returned {@link CompletableFuture}
     * will be notified when the {@link BodyPart} is received fully.
     * {@link AggregatedBodyPart#content()} will return a pooled object, and the caller must ensure
     * to release it. If you don't know what this means, use {@link #aggregate()}.
     */
    CompletableFuture<AggregatedBodyPart> aggregateWithPooledObjects(EventExecutor executor,
                                                                     ByteBufAllocator alloc);

    /**
     * Returns the control name.
     *
     * @return the {@code name} parameter of the {@code "content-disposition"}
     *         header, or {@code null} if not present.
     */
    @Nullable
    default String name() {
        final ContentDisposition contentDisposition = headers().contentDisposition();
        if (contentDisposition != null) {
            return contentDisposition.name();
        } else {
            return null;
        }
    }

    /**
     * Returns the file name.
     *
     * @return the {@code filename} parameter of the {@code "content-disposition"}
     *         header, or {@code null} if not present.
     */
    @Nullable
    default String filename() {
        final ContentDisposition contentDisposition = headers().contentDisposition();
        if (contentDisposition != null) {
            return contentDisposition.filename();
        } else {
            return null;
        }
    }
}
