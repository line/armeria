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

package com.linecorp.armeria.internal.logging;

import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.RequestLogProperty;

/**
 * Collects a preview without allowing observer failures to affect an exchange.
 */
public final class HttpContentPreviewer {
    private static final Logger logger = LoggerFactory.getLogger(HttpContentPreviewer.class);

    private final RequestContext ctx;
    private final BiFunction<? super RequestContext, String, ? extends @Nullable Object> sanitizer;
    private final boolean request;
    @Nullable
    private ContentPreviewer previewer;
    private boolean finished;

    public HttpContentPreviewer(
            RequestContext ctx, ContentPreviewerFactory factory, HttpHeaders headers, boolean request,
            BiFunction<? super RequestContext, String, ? extends @Nullable Object> sanitizer) {
        this.ctx = ctx;
        this.sanitizer = sanitizer;
        this.request = request;
        ctx.logBuilder().defer(request ? RequestLogProperty.REQUEST_CONTENT_PREVIEW
                                       : RequestLogProperty.RESPONSE_CONTENT_PREVIEW);
        try {
            previewer = request ? factory.requestContentPreviewer(ctx, (RequestHeaders) headers)
                                : factory.responseContentPreviewer(ctx, (ResponseHeaders) headers);
            if (previewer.isDisabled()) {
                previewer = null;
            }
        } catch (Exception cause) {
            previewer = null;
            logger.warn("Failed to create a content previewer", cause);
        }
    }

    public boolean isDisabled() {
        return previewer == null;
    }

    public void onData(HttpData data) {
        if (previewer == null) {
            return;
        }
        try {
            if (!previewer.isDisabled()) {
                previewer.onData(data);
            }
        } catch (Exception cause) {
            previewer = null;
            logger.warn("Failed to collect a content preview", cause);
        }
    }

    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        String preview = null;
        try {
            if (previewer != null && !previewer.isDisabled()) {
                final String produced = previewer.produce();
                if (produced != null) {
                    final Object sanitized = sanitizer.apply(ctx, produced);
                    preview = sanitized != null ? sanitized.toString() : "<sanitized>";
                }
            }
        } catch (Exception cause) {
            // Never publish the unsanitized value when the sanitizer fails.
            logger.warn("Failed to produce a content preview", cause);
        }
        if (request) {
            ctx.logBuilder().requestContentPreview(preview);
        } else {
            ctx.logBuilder().responseContentPreview(preview);
        }
    }
}
