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
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A sanitizer for HTTP headers, content, and trailers.
 */
public interface LogSanitizer {

    static LogSanitizer ofRequestLogSanitizer(
            BiFunction<? super RequestContext, ? super RequestHeaders,
                    ? extends @Nullable Object> headersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> contentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> trailersSanitizer
    ) {
        return new RequestLogSanitizer(headersSanitizer, contentSanitizer, trailersSanitizer);
    }

    static LogSanitizer ofResponseLogSanitizer(
            BiFunction<? super RequestContext, ? super ResponseHeaders,
                    ? extends @Nullable Object> headersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> contentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> trailersSanitizer
    ) {
        return new ResponseLogSanitizer(headersSanitizer, contentSanitizer, trailersSanitizer);
    }

    String sanitizeHeaders(RequestContext ctx, HttpHeaders headers);

    String sanitizeContent(RequestContext ctx, Object object);

    String sanitizeTrailers(RequestContext ctx, HttpHeaders trailers);
}
