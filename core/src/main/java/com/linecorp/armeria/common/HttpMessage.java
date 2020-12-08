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

import java.util.function.Function;

import com.linecorp.armeria.common.stream.HttpDeframerHandler;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * A streamed HTTP/2 message.
 */
public interface HttpMessage {

    /**
     * Creates a deframed {@link StreamMessage} which is decoded from a stream of {@link HttpObject}s using
     * the specified {@link HttpDeframerHandler}.
     */
    default <T> StreamMessage<T> deframe(HttpDeframerHandler<T> handler) {
        requireNonNull(handler, "handler");
        return deframe(handler, ByteBufAllocator.DEFAULT);
    }

    /**
     * Creates a deframed {@link StreamMessage} which is decoded from a stream of {@link HttpObject}s using
     * the specified {@link HttpDeframerHandler} and {@link ByteBufAllocator}.
     */
    default <T> StreamMessage<T> deframe(HttpDeframerHandler<T> handler, ByteBufAllocator alloc) {
        requireNonNull(handler, "handler");
        requireNonNull(alloc, "alloc");
        return deframe(handler, alloc, HttpData::byteBuf);
    }

    /**
     * Creates a deframed {@link StreamMessage} which is decoded from a stream of {@link HttpObject}s using
     * the specified {@link HttpDeframerHandler} and {@link ByteBufAllocator} and {@code byteBufConverter}.
     */
    <T> StreamMessage<T> deframe(HttpDeframerHandler<T> handler, ByteBufAllocator alloc,
                                 Function<? super HttpData, ? extends ByteBuf> byteBufConverter);
}
