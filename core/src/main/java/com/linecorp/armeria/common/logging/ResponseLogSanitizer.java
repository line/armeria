/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import java.util.function.BiFunction;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link LogSanitizer} for HTTP response.
 */
class ResponseLogSanitizer implements LogSanitizer {

    BiFunction<? super RequestContext, ? super ResponseHeaders,
            ? extends @Nullable Object> headersSanitizer;
    BiFunction<? super RequestContext, Object,
            ? extends @Nullable Object> contentSanitizer;
    BiFunction<? super RequestContext, ? super HttpHeaders,
            ? extends @Nullable Object> trailersSanitizer;

    ResponseLogSanitizer(
            BiFunction<? super RequestContext, ? super ResponseHeaders,
                    ? extends @Nullable Object> headersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> contentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> trailersSanitizer
    ) {
        this.headersSanitizer = headersSanitizer;
        this.contentSanitizer = contentSanitizer;
        this.trailersSanitizer = trailersSanitizer;
    }

    @Override
    public String sanitizeHeaders(RequestContext ctx, HttpHeaders headers) {
        final Object sanitized = headersSanitizer.apply(ctx, (ResponseHeaders) headers);
        return sanitized != null ? sanitized.toString() : "<sanitized>";
    }

    @Override
    public String sanitizeContent(RequestContext ctx, Object object) {
        final Object sanitized = contentSanitizer.apply(ctx, object);
        return sanitized != null ? sanitized.toString() : "<sanitized>";
    }

    @Override
    public String sanitizeTrailers(RequestContext ctx, HttpHeaders trailers) {
        final Object sanitized = trailersSanitizer.apply(ctx, trailers);
        return sanitized != null ? sanitized.toString() : "<sanitized>";
    }
}
