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
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;

import io.netty.util.AttributeKey;

public final class ContentPreviewingUtil {

    private static final Logger logger = LoggerFactory.getLogger(ContentPreviewingUtil.class);

    private static final AttributeKey<ResponseContentPreviewer> RESPONSE_CONTENT_PREVIEWER_KEY =
            AttributeKey.valueOf(ResponseContentPreviewer.class, "RESPONSE_CONTENT_PREVIEWER");

    @Nullable
    public static ResponseContentPreviewer responseContentPreviewer(RequestContext ctx) {
        return ctx.attr(RESPONSE_CONTENT_PREVIEWER_KEY);
    }

    public static void setResponseContentPreviewer(RequestContext ctx, ResponseContentPreviewer previewer) {
        ctx.setAttr(RESPONSE_CONTENT_PREVIEWER_KEY, previewer);
    }

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
                if (produced != null) {
                    produced = sanitize(contentSanitizer, ctx, produced);
                }
            } catch (Exception e) {
                logger.warn("Unexpected exception while producing the request content preview. " +
                            "previewer: {}", requestContentPreviewer, e);
            }
            logBuilder.requestContentPreview(produced);
            return null;
        });
        return filteredHttpRequest;
    }

    private ContentPreviewingUtil() {}

    @Nullable
    static String sanitize(
            BiFunction<? super RequestContext, String,
                    ? extends @Nullable Object> contentSanitizer,
            RequestContext ctx, String produced) {
        final Object sanitized = contentSanitizer.apply(ctx, produced);
        return sanitized != null ? sanitized.toString() : "<sanitized>";
    }
}
