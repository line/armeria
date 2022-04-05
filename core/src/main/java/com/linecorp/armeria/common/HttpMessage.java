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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageDuplicator;
import com.linecorp.armeria.internal.common.stream.DecodedStreamMessage;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 message.
 */
public interface HttpMessage extends StreamMessage<HttpObject> {

    /**
     * Returns a new {@link StreamMessageDuplicator} that duplicates this {@link HttpMessage} into one or
     * more {@link HttpMessage}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpMessage} anymore after you call this method.
     * To subscribe, call {@link StreamMessageDuplicator#duplicate()} from the returned
     * {@link StreamMessageDuplicator}.
     *
     * @param maxContentLength the maximum content length that the duplicator can hold in its buffer.
     *                         {@link ContentTooLargeException} is raised if the length of the buffered
     *                         {@link HttpData} is greater than this value.
     */
    StreamMessageDuplicator<HttpObject> toDuplicator(long maxContentLength);

    /**
     * Returns a new {@link StreamMessageDuplicator} that duplicates this {@link HttpMessage} into one or
     * more {@link HttpMessage}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpMessage} anymore after you call this method.
     * To subscribe, call {@link StreamMessageDuplicator#duplicate()} from the returned
     * {@link StreamMessageDuplicator}.
     *
     * @param executor the executor to duplicate
     * @param maxContentLength the maximum content length that the duplicator can hold in its buffer.
     *                         {@link ContentTooLargeException} is raised if the length of the buffered
     *                         {@link HttpData} is greater than this value.
     */
    StreamMessageDuplicator<HttpObject> toDuplicator(EventExecutor executor, long maxContentLength);

    /**
     * Creates a decoded {@link StreamMessage} which is decoded from a stream of {@link HttpObject}s using
     * the specified {@link HttpDecoder}.
     */
    default <T> StreamMessage<T> decode(HttpDecoder<T> decoder) {
        requireNonNull(decoder, "decoder");
        return decode(decoder, ByteBufAllocator.DEFAULT);
    }

    /**
     * Creates a decoded {@link StreamMessage} which is decoded from a stream of {@link HttpObject}s using
     * the specified {@link HttpDecoder} and {@link ByteBufAllocator}.
     */
    default <T> StreamMessage<T> decode(HttpDecoder<T> decoder, ByteBufAllocator alloc) {
        return DecodedStreamMessage.of(this, decoder, alloc);
    }

    /**
     * Transforms the {@link HttpData}s emitted by this {@link HttpMessage} by applying the
     * specified {@link Function}.
     */
    HttpMessage mapData(Function<? super HttpData, ? extends HttpData> function);

    /**
     * Transforms the {@linkplain HttpHeaders trailers} emitted by this {@link HttpMessage} by applying the
     * specified {@link Function}.
     */
    HttpMessage mapTrailers(Function<? super HttpHeaders, ? extends HttpHeaders> function);

    /**
     * Applies the specified {@link Consumer} to the {@link HttpData}s
     * emitted by this {@link HttpMessage}.
     */
    HttpMessage peekData(Consumer<? super HttpData> action);

    /**
     * Applies the specified {@link Consumer} to the {@linkplain HttpHeaders trailers}
     * emitted by this {@link HttpMessage}.
     */
    HttpMessage peekTrailers(Consumer<? super HttpHeaders> action);
}
