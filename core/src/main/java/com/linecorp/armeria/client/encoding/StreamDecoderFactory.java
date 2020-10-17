/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.encoding;

import com.linecorp.armeria.common.HttpResponse;

import io.netty.buffer.ByteBufAllocator;

/**
 * An interface that constructs a new {@link StreamDecoder} for a given Content-Encoding header value.
 * A new decoder is valid for the lifetime of an {@link HttpResponse}.
 *
 * @deprecated Use {@link com.linecorp.armeria.common.encoding.StreamDecoderFactory} instead.
 */
@Deprecated
public interface StreamDecoderFactory {

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
     * Returns the value of the Content-Encoding header which this factory applies to.
     */
    String encodingHeaderValue();

    /**
     * Construct a new {@link StreamDecoder} to use to decode an {@link HttpResponse}.
     */
    StreamDecoder newDecoder(ByteBufAllocator alloc);
}
