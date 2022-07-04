/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link StreamMessage} that publishes bytes with {@link HttpData}.
 */
@UnstableApi
public interface ByteStreamMessage extends StreamMessage<HttpData> {

    /**
     * Creates a new {@link ByteStreamMessage} from the specified {@link Publisher}.
     */
    static ByteStreamMessage of(Publisher<? extends HttpData> publisher) {
        requireNonNull(publisher, "publisher");
        if (publisher instanceof ByteStreamMessage) {
            return (ByteStreamMessage) publisher;
        } else {
            return new DefaultByteStreamMessage(StreamMessage.of(publisher));
        }
    }

    /**
     * Adapts this {@link ByteStreamMessage} to {@link InputStream}.
     *
     * <p>For example:<pre>{@code
     * ByteStreamMessage streamMessage = ByteStreamMessage.of(...);
     * InputStream inputStream = streamMessage.toInputStream();
     * byte[] expected = "foobarbaz".getBytes();
     *
     * ByteBuf result = Unpooled.buffer();
     * int read;
     * while ((read = inputStream.read()) != -1) {
     *     result.writeByte(read);
     * }
     *
     * int readableBytes = result.readableBytes();
     * byte[] actual = new byte[readableBytes];
     * for (int i = 0; i < readableBytes; i++) {
     *     actual[i] = result.readByte();
     * }
     * assert Arrays.equals(actual, expected);
     * assert inputStream.available() == 0;
     * }</pre>
     */
    default InputStream toInputStream() {
        return StreamMessage.super.toInputStream(Function.identity());
    }

    /**
     * Writes this {@link ByteStreamMessage} to the given {@link Path} with {@link OpenOption}s.
     * If the {@link OpenOption} is not specified, defaults to {@link StandardOpenOption#CREATE},
     * {@link StandardOpenOption#TRUNCATE_EXISTING} and {@link StandardOpenOption#WRITE}.
     *
     * <p>Example:<pre>{@code
     * Path destination = Paths.get("foo.bin");
     * ByteStreamMessage streamMessage = ByteStreamMessage.of(...);
     * streamMessage.writeTo(destination);
     * }</pre>
     *
     * @see StreamMessages#writeTo(StreamMessage, Path, OpenOption...)
     */
    default CompletableFuture<Void> writeTo(Path destination, OpenOption... options) {
        return StreamMessage.super.writeTo(Function.identity(), destination, options);
    }

    /**
     * Sets the specified range of bytes to read from this {@link ByteStreamMessage}.
     * <pre>{@code
     * StreamMessage<HttpData> source = StreamMessage.of(HttpData.ofUtf8("12345"),
     *                                                   HttpData.ofUtf8("67890"),
     *                                                   HttpData.ofUtf8("12345"));
     *
     * // Read 8 bytes from the index 4
     * List<HttpData> collected = ByteStreamMessage.of(source).range(4, 8).collect().join();
     * assert collected.equals(List.of(HttpData.ofUtf8("5"),
     *                                 HttpData.ofUtf8("67890"),
     *                                 HttpData.ofUtf8("12")));
     * }</pre>
     *
     * @throws IllegalArgumentException if the {@code offset} is negative or the {@code length} is non-positive.
     * @throws IllegalStateException if this {@link ByteStreamMessage} is subscribed already.
     */
    ByteStreamMessage range(long offset, long length);

    /**
     * Collects the bytes published by this {@link ByteStreamMessage}.
     * The returned {@link CompletableFuture} will be notified when the elements are fully consumed.
     *
     * <pre>{@code
     * StreamMessage<HttpData> source = StreamMessage.of(HttpData.wrap(new byte[] { 1, 2, 3 }),
     *                                                   HttpData.wrap(new byte[] { 4, 5, 6 }));
     *
     * byte[] collected = ByteStreamMessage.of(source).collectBytes().join();
     * assert Arrays.equals(collected, new byte[] { 1, 2, 3, 4, 5, 6 });
     * }</pre>
     */
    default CompletableFuture<byte[]> collectBytes() {
        return collectBytes(defaultSubscriberExecutor());
    }

    /**
     * Collects the bytes published by this {@link ByteStreamMessage}.
     * The returned {@link CompletableFuture} will be notified when the elements are fully consumed.
     *
     * <pre>{@code
     * StreamMessage<HttpData> source = StreamMessage.of(HttpData.wrap(new byte[] { 1, 2, 3 }),
     *                                                   HttpData.wrap(new byte[] { 4, 5, 6 }));
     *
     * byte[] collected = ByteStreamMessage.of(source).collectBytes(executor).join();
     * assert Arrays.equals(collected, new byte[] { 1, 2, 3, 4, 5, 6 });
     * }</pre>
     *
     * @param executor the executor to collect the {@link HttpData}.
     */
    default CompletableFuture<byte[]> collectBytes(EventExecutor executor) {
        return collect(executor).thenApply(data -> {
            int totalSize = 0;
            for (HttpData httpData : data) {
                totalSize += httpData.length();
            }
            final byte[] bytes = new byte[totalSize];
            int position = 0;
            for (HttpData httpData : data) {
                final int length = httpData.length();
                System.arraycopy(httpData.array(), 0, bytes, position, length);
                position += length;
            }
            return bytes;
        });
    }
}
