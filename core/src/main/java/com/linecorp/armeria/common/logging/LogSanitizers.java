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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Functions;

/**
 * A holder class that has sanitizers for HTTP request/response header, content, and trailer.
 */
public class LogSanitizers<T extends HttpHeaders> {

    private final BiFunction<? super RequestContext, ? super T, ? extends @Nullable Object> headersSanitizer;

    private final BiFunction<? super RequestContext, Object, ? extends @Nullable Object> contentSanitizer;

    private final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
            trailersSanitizer;

    /**
     * Returns {@link LogSanitizers} with no sanitizers.
     */
    public static <T extends HttpHeaders> LogSanitizers<T> of() {
        return new LogSanitizers<>(Functions.second(), Functions.second(), Functions.second());
    }

    /**
     * Returns a newly created {@link LogSanitizers}.
     */
    public LogSanitizers(
            BiFunction<? super RequestContext, ? super T, ? extends @Nullable Object> headersSanitizer,
            BiFunction<? super RequestContext, Object, ? extends @Nullable Object> contentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
                    trailersSanitizer) {
        this.headersSanitizer = headersSanitizer;
        this.contentSanitizer = contentSanitizer;
        this.trailersSanitizer = trailersSanitizer;
    }

    /**
     * Returns {@link BiFunction} to sanitize a header.
     */
    public BiFunction<? super RequestContext, ? super T, ? extends @Nullable Object> headersSanitizer() {
        return headersSanitizer;
    }

    /**
     * Returns {@link BiFunction} to sanitize a content.
     */
    public BiFunction<? super RequestContext, Object, ? extends @Nullable Object> contentSanitizer() {
        return contentSanitizer;
    }

    /**
     * Returns {@link BiFunction} to sanitize a trailer.
     */
    public BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
    trailersSanitizer() {
        return trailersSanitizer;
    }
}
