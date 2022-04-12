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

import io.netty.buffer.ByteBufAllocator;

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
     * Sets the specified {@link ByteBufAllocator} to this {@link ByteStreamMessage}.
     * @return {@code this}
     */
    ByteStreamMessage alloc(ByteBufAllocator alloc);

    /**
     * Skips the specified bytes in this {@link ByteStreamMessage}.
     * <pre>{@code
     * StreamMessage<HttpData> source = StreamMessage.of(HttpData.ofUtf8("12345"),
     *                                                   HttpData.ofUtf8("67890"));
     *
     * List<HttpData> collected = ByteStreamMessage.of(source).skipBytes(4).collect().join();
     * assert collected.equals(List.of(HttpData.ofUtf8("5"), HttpData.ofUtf8("67890"));
     * }</pre>
     *
     * @throws IllegalArgumentException if the {@code numBytes} is non-positive.
     */
    ByteStreamMessage skipBytes(int numBytes);

    /**
     * Sets the maximum allowed bytes by this {@link ByteStreamMessage}.
     *
     * <pre>{@code
     * StreamMessage<HttpData> source = StreamMessage.of(HttpData.ofUtf8("12345"),
     *                                                   HttpData.ofUtf8("67890"));
     *
     * List<HttpData> collected = ByteStreamMessage.of(source).takeBytes(6).collect().join();
     * assert collected.equals(List.of(HttpData.ofUtf8("12345"), HttpData.ofUtf8("6"));
     * }</pre>
     *
     * @throws IllegalArgumentException if the {@code numBytes} is non-positive.
     */
    ByteStreamMessage takeBytes(int numBytes);

    /**
     * Sets the maximum allowed bytes for each {@link HttpData} in this {@link ByteStreamMessage}.
     *
     * <pre>{@code
     * StreamMessage<HttpData> source = StreamMessage.of(HttpData.ofUtf8("12345"),
     *                                                   HttpData.ofUtf8("67890"));
     *
     * List<HttpData> collected = ByteStreamMessage.of(source).bufferSize(3).collect().join();
     * assert collected.equals(List.of(HttpData.ofUtf8("123"), HttpData.ofUtf8("456"),
     *                                 HttpData.ofUtf8("789"), HttpData.ofUtf8("0")));
     * }</pre>
     *
     * @throws IllegalArgumentException if the {@code numBytes} is non-positive.
     */
    ByteStreamMessage bufferSize(int numBytes);
}
