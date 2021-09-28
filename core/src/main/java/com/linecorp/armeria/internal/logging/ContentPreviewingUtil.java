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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

public final class ContentPreviewingUtil {

    private static final Logger logger = LoggerFactory.getLogger(ContentPreviewingUtil.class);

    /**
     * Sets up the request {@link ContentPreviewer} to set
     * {@link RequestLogBuilder#requestContentPreview(String)} when the preview is available.
     */
    public static HttpRequest setUpRequestContentPreviewer(
            RequestContext ctx, HttpRequest req, ContentPreviewer requestContentPreviewer,
            BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer) {
        requireNonNull(ctx, "ctx");
        requireNonNull(req, "req");
        requireNonNull(requestContentPreviewer, "requestContentPreviewer");
        requireNonNull(contentSanitizer, "contentSanitizer");
        if (requestContentPreviewer.isDisabled()) {
            return req;
        }

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.defer(RequestLogProperty.REQUEST_CONTENT_PREVIEW);
        req.whenComplete().handle((unused, unused1) -> {
            // The HttpRequest cannot be subscribed so call requestContentPreview(null) to make sure that the
            // log is complete.
            logBuilder.requestContentPreview(null);
            return null;
        });
        final FilteredHttpRequest filteredHttpRequest = new FilteredHttpRequest(req) {
            @Override
            protected HttpObject filter(HttpObject obj) {
                if (obj instanceof HttpData) {
                    requestContentPreviewer.onData((HttpData) obj);
                }
                return obj;
            }
        };
        filteredHttpRequest.whenComplete().handle((unused, cause) -> {
            @Nullable String produced = null;
            try {
                produced = requestContentPreviewer.produce();
            } catch (Exception e) {
                logger.warn("Unexpected exception while producing the request content preview. " +
                            "previewer: {}", requestContentPreviewer, e);
            }
            if (produced != null) {
                produced = sanitize(contentSanitizer, ctx, produced);
            }
            logBuilder.requestContentPreview(produced);
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
        @Nullable
        ContentPreviewer responseContentPreviewer;

        protected ContentPreviewerHttpResponse(
                HttpResponse delegate, ContentPreviewerFactory factory, RequestContext ctx,
                BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer) {
            super(delegate);
            this.factory = factory;
            this.ctx = ctx;
            whenComplete().handle((unused, cause) -> {
                if (responseContentPreviewer != null) {
                    @Nullable String produced = null;
                    try {
                        produced = responseContentPreviewer.produce();
                    } catch (Exception e) {
                        logger.warn("Unexpected exception while producing the response content preview. " +
                                    "previewer: {}", responseContentPreviewer, e);
                    }
                    if (produced != null) {
                        produced = sanitize(contentSanitizer, ctx, produced);
                    }
                    ctx.logBuilder().responseContentPreview(produced);
                } else {
                    // Call requestContentPreview(null) to make sure that the log is complete.
                    ctx.logBuilder().responseContentPreview(null);
                }
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
                final ContentPreviewer contentPreviewer = factory.responseContentPreviewer(ctx, resHeaders);
                if (!contentPreviewer.isDisabled()) {
                    responseContentPreviewer = contentPreviewer;
                }
            } else if (obj instanceof HttpData) {
                if (responseContentPreviewer != null) {
                    responseContentPreviewer.onData((HttpData) obj);
                }
            }
            return obj;
        }
    }

    private ContentPreviewingUtil() {}

    @Nullable
    private static String sanitize(
            BiFunction<? super RequestContext, String,
                    ? extends @Nullable Object> contentSanitizer,
            RequestContext ctx, String produced) {
        final Object sanitized = contentSanitizer.apply(ctx, produced);
        return sanitized != null ? sanitized.toString() : "<sanitized>";
    }
}
