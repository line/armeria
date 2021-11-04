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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ResourceLeakDetector;

/**
 * Represents binary data.
 */
@UnstableApi
public interface BinaryData extends SafeCloseable {

    /**
     * Returns the underlying byte array of this data. Any changes made in the returned array affects
     * the content of this data.
     */
    byte[] array();

    /**
     * Returns the length of this data.
     */
    int length();

    /**
     * Returns whether the {@link #length()} of this data is 0.
     */
    default boolean isEmpty() {
        return length() == 0;
    }

    /**
     * Decodes this data into a {@link String}.
     *
     * @param charset the {@link Charset} to use for decoding this data
     *
     * @return the decoded {@link String}
     */
    String toString(Charset charset);

    /**
     * Decodes this data into a {@link String} using UTF-8 encoding.
     *
     * @return the decoded {@link String}
     */
    default String toStringUtf8() {
        return toString(StandardCharsets.UTF_8);
    }

    /**
     * Decodes this data into a {@link String} using US-ASCII encoding.
     *
     * @return the decoded {@link String}
     */
    default String toStringAscii() {
        return toString(StandardCharsets.US_ASCII);
    }

    /**
     * Returns a new {@link InputStream} that is sourced from this data.
     */
    InputStream toInputStream();

    /**
     * Returns a new {@link Reader} that is sourced from this data and decoded using the specified
     * {@link Charset}.
     */
    default Reader toReader(Charset charset) {
        requireNonNull(charset, "charset");
        return new InputStreamReader(toInputStream(), charset);
    }

    /**
     * Returns a new {@link Reader} that is sourced from this data and decoded using
     * {@link StandardCharsets#UTF_8}.
     */
    default Reader toReaderUtf8() {
        return toReader(StandardCharsets.UTF_8);
    }

    /**
     * Returns a new {@link Reader} that is sourced from this data and decoded using
     * {@link StandardCharsets#US_ASCII}.
     */
    default Reader toReaderAscii() {
        return toReader(StandardCharsets.US_ASCII);
    }

    /**
     * (Advanced users only) Returns whether this data is pooled. Note, if this method returns {@code true},
     * you must call {@link #close()} once you no longer need this data, because its underlying {@link ByteBuf}
     * will not be released automatically.
     *
     * @see PooledObjects
     */
    boolean isPooled();

    /**
     * (Advanced users only) Returns a new duplicate of the underlying {@link ByteBuf} of this data.
     * This method does not transfer the ownership of the underlying {@link ByteBuf}, i.e. the reference
     * count of the {@link ByteBuf} does not change. If this data is not pooled, the returned {@link ByteBuf}
     * is not pooled, either, which means you need to worry about releasing it only when you created this data
     * with pooled objects. Any changes made in the content of the returned {@link ByteBuf} affects
     * the content of this data.
     *
     * @see PooledObjects
     */
    default ByteBuf byteBuf() {
        return byteBuf(ByteBufAccessMode.DUPLICATE);
    }

    /**
     * (Advanced users only) Returns a new duplicate, retained duplicate or direct copy of the underlying
     * {@link ByteBuf} of this data based on the specified {@link ByteBufAccessMode}.
     * This method does not transfer the ownership of the underlying {@link ByteBuf}, i.e. the reference
     * count of the {@link ByteBuf} does not change. If this data is not pooled, the returned {@link ByteBuf}
     * is not pooled, either, which means you need to worry about releasing it only when you created this data
     * with pooled objects. Any changes made in the content of the returned {@link ByteBuf} affects
     * the content of this data.
     *
     * @see PooledObjects
     */
    ByteBuf byteBuf(ByteBufAccessMode mode);

    /**
     * (Advanced users only) Returns a new slice, retained slice or direct copy of the underlying
     * {@link ByteBuf} of this data based on the specified {@link ByteBufAccessMode}.
     * This method does not transfer the ownership of the underlying {@link ByteBuf}, i.e. the reference
     * count of the {@link ByteBuf} does not change. If this data is not pooled, the returned {@link ByteBuf}
     * is not pooled, either, which means you need to worry about releasing it only when you created this data
     * with pooled objects. Any changes made in the content of the returned {@link ByteBuf} affects
     * the content of this data.
     *
     * @see PooledObjects
     */
    ByteBuf byteBuf(int offset, int length, ByteBufAccessMode mode);

    /**
     * (Advanced users only) Records the current access location of this data for debugging purposes.
     * If this data is determined to be leaked, the information recorded by this operation will be provided to
     * you via {@link ResourceLeakDetector}.
     */
    default void touch(@Nullable Object hint) {}

    /**
     * Releases the underlying {@link ByteBuf} if this data was created with pooled objects.
     * Otherwise, this method does nothing. You may want to call this method to reclaim the underlying
     * {@link ByteBuf} when using operations that return pooled objects, such as:
     * <ul>
     *   <li>{@link StreamMessage#subscribe(Subscriber, SubscriptionOption...)} with
     *       {@link SubscriptionOption#WITH_POOLED_OBJECTS}</li>
     *   <li>{@link HttpRequest#aggregateWithPooledObjects(ByteBufAllocator)}</li>
     *   <li>{@link HttpResponse#aggregateWithPooledObjects(ByteBufAllocator)}</li>
     *   <li>{@link HttpFile#aggregateWithPooledObjects(Executor, ByteBufAllocator)}</li>
     * </ul>
     * If you don't use such operations, you don't need to call this method.
     *
     * @see PooledObjects
     */
    @Override
    void close();
}
