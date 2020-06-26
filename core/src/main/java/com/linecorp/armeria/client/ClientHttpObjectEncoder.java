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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.HttpObjectEncoder;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

/**
 * Converts an {@link HttpObject} into a protocol-specific object and writes it into a {@link Channel}.
 */
interface ClientHttpObjectEncoder extends HttpObjectEncoder {

    /**
     * Writes a {@link RequestHeaders}.
     */
    default ChannelFuture writeHeaders(int id, int streamId, RequestHeaders headers, boolean endStream) {
        assert eventLoop().inEventLoop();
        if (isClosed()) {
            return newFailedFuture(UnprocessedRequestException.of(ClosedSessionException.get()));
        }

        return doWriteHeaders(id, streamId, headers, endStream);
    }

    /**
     * Writes a {@link RequestHeaders}.
     */
    ChannelFuture doWriteHeaders(int id, int streamId, RequestHeaders headers, boolean endStream);
}
