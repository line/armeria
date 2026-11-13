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

import static com.linecorp.armeria.internal.logging.ContentPreviewingUtil.setUpRequestContentPreviewer;

import com.google.common.base.Ascii;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.server.HttpDecodedRequest;
import com.linecorp.armeria.server.logging.ContentPreview;

final class ServerRequestContent {
    static HttpRequest prepare(ServiceRequestContext ctx, HttpRequest request,
                               @Nullable StreamDecoderFactory factory) {
        final ServerOptions options = ctx.config().options();
        final HttpRequest originalRequest = request;
        final RequestHeaders headers = request.headers();
        if (factory != null) {
            final RequestHeaders decodedHeaders = headers.toBuilder()
                    .removeAndThen(HttpHeaderNames.CONTENT_ENCODING)
                    .removeAndThen(HttpHeaderNames.CONTENT_LENGTH).build();
            request = new HttpDecodedRequest(request.withHeaders(decodedHeaders), factory, ctx.alloc(),
                                             Ints.saturatedCast(ctx.maxRequestLength()));
        }
        final ContentPreview config = options.get(ServerOptions.CONTENT_PREVIEW);
        final ContentPreviewerFactory previewerFactory = config.factory();
        if (previewerFactory != null && !request.headers().contains(HttpHeaderNames.CONTENT_ENCODING)) {
            request = setUpRequestContentPreviewer(ctx, request, previewerFactory,
                                                  config.requestPreviewSanitizer());
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
