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

package com.linecorp.armeria.common.encoding;

import java.util.List;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpMessage;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * An interface that constructs a new {@link StreamDecoder} for a given Content-Encoding header value.
 * A new decoder is valid for the lifetime of an {@link HttpMessage}.
 */
public interface StreamDecoderFactory extends com.linecorp.armeria.client.encoding.StreamDecoderFactory {

    /**
     * Returns all available {@link StreamDecoderFactory}s.
     */
    @UnstableApi
    static List<StreamDecoderFactory> all() {
        return StreamDecoderFactories.ALL;
    }

    /**
     * Returns the {@link StreamDecoderFactory} for {@code "deflate"} content encoding.
     */
    static StreamDecoderFactory deflate() {
        return StreamDecoderFactories.DEFLATE;
    }

    /**
     * Returns the {@link StreamDecoderFactory} for {@code "gzip"} content encoding.
     */
    static StreamDecoderFactory gzip() {
        return StreamDecoderFactories.GZIP;
    }

    /**
     * Returns the {@link StreamDecoderFactory} for {@code "br"} content encoding.
     */
    static StreamDecoderFactory brotli() {
        return StreamDecoderFactories.BROTLI;
    }

    /**
     * Returns the {@link StreamDecoderFactory} for
     * <a href="https://github.com/google/snappy/blob/27f34a580be4a3becf5f8c0cba13433f53c21337/framing_format.txt">"x-snappy-framed"</a>
     * content encoding.
     */
    static StreamDecoderFactory snappy() {
        return StreamDecoderFactories.SNAPPY;
    }

    /**
     * Returns the value of the Content-Encoding header which this factory applies to.
     */
    @Override
    String encodingHeaderValue();

    /**
     * Construct a new {@link StreamDecoder} to use to decode an {@link HttpMessage}.
     *
     * @param alloc the {@link ByteBufAllocator} to allocate a new {@link ByteBuf} for the decoded
     *              {@link HttpMessage}.
     */
    @Override
    default StreamDecoder newDecoder(ByteBufAllocator alloc) {
        return newDecoder(alloc, 0);
    }

    /**
     * Construct a new {@link StreamDecoder} to use to decode an {@link HttpMessage}.
     *
     * @param alloc the {@link ByteBufAllocator} to allocate a new {@link ByteBuf} for the decoded
     *              {@link HttpMessage}.
     * @param maxLength the maximum allowed length of a decoded content. If the total length of the decoded
     *                  content exceeds {@code maxLength}, a {@link ContentTooLargeException} will be raised.
     */
    @UnstableApi
    @Override
    StreamDecoder newDecoder(ByteBufAllocator alloc, int maxLength);
}
