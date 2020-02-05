/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.common.grpc.protocol;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.UnstableApi;

/**
 * Utility for creating response trailers for a gRPC status. Trailers are only returned from a server.
 */
@UnstableApi
public final class GrpcTrailersUtil {

    /**
     * Converts the given gRPC status code, and optionally an error message, to headers. The headers will be
     * either trailers-only or normal trailers based on {@code headersSent}, whether leading headers have
     * already been sent to the client.
     */
    public static HttpHeadersBuilder statusToTrailers(int code, @Nullable String message, boolean headersSent) {
        final HttpHeadersBuilder trailers;
        if (headersSent) {
            // Normal trailers.
            trailers = HttpHeaders.builder();
        } else {
            // Trailers only response
            trailers = ResponseHeaders.builder()
                                      .endOfStream(true)
                                      .add(HttpHeaderNames.STATUS, HttpStatus.OK.codeAsText())
                                      .add(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto");
        }
        trailers.add(GrpcHeaderNames.GRPC_STATUS, Integer.toString(code));

        if (message != null) {
            trailers.add(GrpcHeaderNames.GRPC_MESSAGE, StatusMessageEscaper.escape(message));
        }

        return trailers;
    }

    private GrpcTrailersUtil() {}
}
