/*
 * Copyright 2024 LINE Corporation
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

import com.google.common.base.Ascii;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.FilteredHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.internal.server.HttpDecodedRequest;
import com.linecorp.armeria.server.logging.ContentPreview;

final class ServerRequestContent {
    static HttpRequest prepare(ServiceRequestContext ctx, HttpRequest request) {
        final ServerOptions options = ctx.config().options();
        final HttpRequest originalRequest = request;
        final RequestHeaders headers = request.headers();
        final StreamDecoderFactory factory = decoderFactory(options, headers);
        if (factory != null) {
            final RequestHeaders decodedHeaders = headers.toBuilder()
                    .removeAndThen(HttpHeaderNames.CONTENT_ENCODING)
                    .removeAndThen(HttpHeaderNames.CONTENT_LENGTH).build();
            request = new HttpDecodedRequest(request.withHeaders(decodedHeaders), factory, ctx.alloc(),
                                             Ints.saturatedCast(ctx.maxRequestLength()));
        }
        final ContentPreview config = options.get(ServerOptions.CONTENT_PREVIEW);
        if (config.factory() != null && !request.headers().contains(HttpHeaderNames.CONTENT_ENCODING)) {
            if (request.isEmpty()) {
                ctx.logBuilder().requestContentPreview("");
            } else {
                final HttpContentPreviewer previewer = new HttpContentPreviewer(ctx, config,
                                                                               request.headers(), true);
                request = new FilteredHttpRequest(request) {
                    @Override
                    protected HttpObject filter(HttpObject object) {
                        if (object instanceof HttpData) {
                            previewer.onData((HttpData) object);
                        }
                        return object;
                    }
                };
                request.whenComplete().handle((unused, cause) -> {
                    previewer.finish();
                    return null;
                });
            }
        }
        if (request != originalRequest) {
            ctx.updateRequest(request);
        }
        return request;
    }

    @Nullable
    static StreamDecoderFactory decoderFactory(ServerOptions options, RequestHeaders headers) {
        final String encoding = headers.get(HttpHeaderNames.CONTENT_ENCODING);
        if (encoding != null) {
            for (StreamDecoderFactory factory : options.get(ServerOptions.DECOMPRESSION)) {
                if (Ascii.equalsIgnoreCase(encoding, factory.encodingHeaderValue())) {
                    return factory;
                }
            }
        }
        return null;
    }

    private ServerRequestContent() {}
}
