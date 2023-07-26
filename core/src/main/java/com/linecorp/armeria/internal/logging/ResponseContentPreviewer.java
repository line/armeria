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

package com.linecorp.armeria.internal.logging;

import static com.linecorp.armeria.internal.logging.ContentPreviewingUtil.sanitize;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

public final class ResponseContentPreviewer {

    private static final Logger logger = LoggerFactory.getLogger(ResponseContentPreviewer.class);

    public static ResponseContentPreviewer of(
            ContentPreviewerFactory factory, RequestContext ctx,
            BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer) {
        requireNonNull(factory, "factory");
        requireNonNull(ctx, "ctx");
        requireNonNull(contentSanitizer, "contentSanitizer");
        return new ResponseContentPreviewer(factory, ctx, contentSanitizer);
    }

    private final ContentPreviewerFactory factory;
    private final RequestContext ctx;
    private final BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer;

    @Nullable
    private CompletableFuture<?> produceFuture;

    private ResponseContentPreviewer(
            ContentPreviewerFactory factory, RequestContext ctx,
            BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer) {
        this.factory = factory;
        this.ctx = ctx;
        this.contentSanitizer = contentSanitizer;
    }

    public HttpResponse setUp(HttpResponse res) {
        if (produceFuture != null) {
            produceFuture.cancel(false);
            produceFuture = null;
        }
        return new ContentPreviewerHttpResponse(res, factory, ctx, contentSanitizer);
    }

    private class ContentPreviewerHttpResponse extends FilteredHttpResponse {

        private final ContentPreviewerFactory factory;
        private final RequestContext ctx;
        @Nullable
        private ContentPreviewer responseContentPreviewer;

        protected ContentPreviewerHttpResponse(
                HttpResponse delegate, ContentPreviewerFactory factory, RequestContext ctx,
                BiFunction<? super RequestContext, String, ? extends @Nullable Object> contentSanitizer) {
            super(delegate);
            this.factory = factory;
            this.ctx = ctx;
            produceFuture = whenComplete().handle((unused, cause) -> {
                if (responseContentPreviewer != null) {
                    @Nullable String produced = null;
                    try {
                        produced = responseContentPreviewer.produce();
                        if (produced != null) {
                            produced = sanitize(contentSanitizer, ctx, produced);
                        }
                    } catch (Exception e) {
                        logger.warn("Unexpected exception while producing the response content preview. " +
                                    "previewer: {}", responseContentPreviewer, e);
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
}
