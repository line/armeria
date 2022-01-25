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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.HttpObjectEncoder;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

/**
 * Converts an {@link HttpObject} into a protocol-specific object and writes it into a {@link Channel}.
 */
interface ServerHttpObjectEncoder extends HttpObjectEncoder {

    /**
     * Writes a {@link ResponseHeaders}.
     */
    default ChannelFuture writeHeaders(int id, int streamId, ResponseHeaders headers, boolean endStream) {
        return writeHeaders(id, streamId, headers, endStream, true);
    }

    /**
     * Writes a {@link ResponseHeaders}.
     */
    default ChannelFuture writeHeaders(int id, int streamId, ResponseHeaders headers, boolean endStream,
                                       boolean isTrailersEmpty) {
        assert eventLoop().inEventLoop();
        if (isClosed()) {
            return newClosedSessionFuture();
        }

        return doWriteHeaders(id, streamId, headers, endStream, isTrailersEmpty);
    }

    /**
     * Writes a {@link ResponseHeaders}.
     */
    ChannelFuture doWriteHeaders(int id, int streamId, ResponseHeaders headers, boolean endStream,
                                 boolean isTrailersEmpty);

    /**
     * Tells whether the {@link ResponseHeaders} is sent.
     */
    boolean isResponseHeadersSent(int id, int streamId);

    /**
     * Writes an error response.
     */
    default ChannelFuture writeErrorResponse(int id,
                                             int streamId,
                                             ServiceConfig serviceConfig,
                                             @Nullable RequestHeaders headers,
                                             HttpStatus status,
                                             @Nullable String message,
                                             @Nullable Throwable cause) {

        final AggregatedHttpResponse res =
                serviceConfig.server().config().errorHandler()
                             .onProtocolViolation(serviceConfig, headers, status, message, cause);
        assert res != null;

        final HttpData content = res.content();
        boolean transferredContent = false;
        try {
            final ResponseHeaders resHeaders = res.headers();
            final HttpHeaders resTrailers = res.trailers();
            if (resTrailers.isEmpty()) {
                if (content.isEmpty()) {
                    return writeHeaders(id, streamId, resHeaders, true);
                }

                writeHeaders(id, streamId, resHeaders, false);
                transferredContent = true;
                return writeData(id, streamId, content, true);
            }

            writeHeaders(id, streamId, resHeaders, false);
            if (!content.isEmpty()) {
                transferredContent = true;
                writeData(id, streamId, content, false);
            }
            return writeTrailers(id, streamId, resTrailers);
        } finally {
            if (!transferredContent) {
                content.close();
            }
        }
    }
}
