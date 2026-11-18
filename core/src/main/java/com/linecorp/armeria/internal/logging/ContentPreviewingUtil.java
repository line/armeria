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

package com.linecorp.armeria.internal.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.linecorp.armeria.common.FilteredHttpRequest;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

public final class ContentPreviewingUtil {

    /**
     * Sets up the request {@link ContentPreviewer} to set
     * {@link RequestLogBuilder#requestContentPreview(String)} when the preview is available.
     */
    public static HttpRequest setUpRequestContentPreviewer(
            RequestContext ctx, HttpRequest req, ContentPreviewerFactory factory,
            BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer) {
        requireNonNull(ctx, "ctx");
        requireNonNull(req, "req");
        requireNonNull(factory, "factory");
        requireNonNull(contentSanitizer, "contentSanitizer");
        if (req.isEmpty()) {
            ctx.logBuilder().requestContentPreview("");
            return req;
        }
        final HttpContentPreviewer previewer =
                new HttpContentPreviewer(ctx, factory, req.headers(), true, contentSanitizer);
        if (previewer.isDisabled()) {
            previewer.finish();
            return req;
        }

        final FilteredHttpRequest filteredHttpRequest = new FilteredHttpRequest(req) {
            @Override
            protected HttpObject filter(HttpObject obj) {
                if (obj instanceof HttpData) {
                    previewer.onData((HttpData) obj);
                }
                return obj;
            }
        };
        filteredHttpRequest.whenComplete().handle((unused, cause) -> {
            previewer.finish();
            return null;
        });
        return filteredHttpRequest;
    }

    /**
     * Sets up the response {@link ContentPreviewer} to set
     * {@link RequestLogBuilder#responseContentPreview(String)} when the preview is available.
     */
    public static HttpResponse setUpResponseContentPreviewer(
            ContentPreviewerFactory factory, RequestContext ctx, HttpResponse res,
            BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer) {
        requireNonNull(factory, "factory");
        requireNonNull(ctx, "ctx");
        requireNonNull(res, "res");
        requireNonNull(contentSanitizer, "contentSanitizer");
        return new ContentPreviewerHttpResponse(res, factory, ctx, contentSanitizer);
    }

    private static class ContentPreviewerHttpResponse extends FilteredHttpResponse {

        private final ContentPreviewerFactory factory;
        private final RequestContext ctx;
        private final BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer;
        @Nullable
        private HttpContentPreviewer responseContentPreviewer;

        protected ContentPreviewerHttpResponse(
                HttpResponse delegate, ContentPreviewerFactory factory, RequestContext ctx,
                BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer) {
            super(delegate);
            this.factory = factory;
            this.ctx = ctx;
            this.contentSanitizer = contentSanitizer;
            whenComplete().handle((unused, cause) -> {
                if (responseContentPreviewer == null) {
                    ctx.logBuilder().responseContentPreview(null);
                    return null;
                }
                responseContentPreviewer.finish();
                return null;
            });
        }

        @Override
        protected HttpObject filter(HttpObject obj) {
            if (obj instanceof ResponseHeaders) {
                final ResponseHeaders resHeaders = (ResponseHeaders) obj;

                // Skip informational headers.
                final String status = resHeaders.get(HttpHeaderNames.STATUS);
                if (ArmeriaHttpUtil.isInformational(status)) {
                    return obj;
                }
                responseContentPreviewer =
                        new HttpContentPreviewer(ctx, factory, resHeaders, false, contentSanitizer);
            } else if (obj instanceof HttpData) {
                if (responseContentPreviewer != null) {
                    responseContentPreviewer.onData((HttpData) obj);
                }
            }
            return obj;
        }
    }

    private ContentPreviewingUtil() {}
}
