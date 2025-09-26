/*
 * Copyright 2023 LINE Corporation
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

import java.io.OutputStream;
import java.util.List;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBufOutputStream;

/**
 * An interface specifying for which {@link HttpHeaderNames#ACCEPT_ENCODING} header value
 * this factory creates a new {@link OutputStream} that applies the corresponding encoding.
 */
public interface StreamEncoderFactory {
    /**
     * Returns all available {@link StreamDecoderFactory}s.
     *
     * @see StreamEncoderFactories#ALL
     */
    @UnstableApi
    static List<StreamEncoderFactory> all() {
        return StreamEncoderFactories.ALL;
    }

    /**
     * Returns the value of the {@link HttpHeaderNames#ACCEPT_ENCODING} header which this factory applies to.
     */
    String encodingHeaderValue();

    /**
     * Constructs a new {@link OutputStream} which applies the factory-specific encoding
     * and writes to the specified {@link ByteBufOutputStream}.
     */
    OutputStream newEncoder(ByteBufOutputStream os);
}
