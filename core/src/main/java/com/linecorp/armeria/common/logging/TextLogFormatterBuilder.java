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

import static com.google.common.base.MoreObjects.firstNonNull;

import java.util.function.BiFunction;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder implementation for {@link TextLogFormatter}.
 */
@UnstableApi
public final class TextLogFormatterBuilder extends AbstractLogFormatterBuilder<String> {

    private boolean includeContext = true;

    TextLogFormatterBuilder() {}

    @Override
    public TextLogFormatterBuilder requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable String> requestHeadersSanitizer) {
        return (TextLogFormatterBuilder) super.requestHeadersSanitizer(requestHeadersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable String> responseHeadersSanitizer) {
        return (TextLogFormatterBuilder) super.responseHeadersSanitizer(responseHeadersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable String> requestTrailersSanitizer) {
        return (TextLogFormatterBuilder) super.requestTrailersSanitizer(requestTrailersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable String> responseTrailersSanitizer) {
        return (TextLogFormatterBuilder) super.responseTrailersSanitizer(responseTrailersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder headersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable String> headersSanitizer) {
        return (TextLogFormatterBuilder) super.headersSanitizer(headersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends @Nullable String> requestContentSanitizer) {
        return (TextLogFormatterBuilder) super.requestContentSanitizer(requestContentSanitizer);
    }

    @Override
    public TextLogFormatterBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends @Nullable String> responseContentSanitizer) {
        return (TextLogFormatterBuilder) super.responseContentSanitizer(responseContentSanitizer);
    }

    @Override
    public TextLogFormatterBuilder contentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends @Nullable String> contentSanitizer) {
        return (TextLogFormatterBuilder) super.contentSanitizer(contentSanitizer);
    }

    /**
     * Sets whether to include stringified {@link RequestContext} in the result of
     * {@link LogFormatter#formatRequest(RequestOnlyLog)} and {@link LogFormatter#formatResponse(RequestLog)}.
     * The context is included by default.
     */
    public TextLogFormatterBuilder includeContext(boolean includeContext) {
        this.includeContext = includeContext;
        return this;
    }

    /**
     * Returns a newly-created text {@link LogFormatter} based on the properties of this builder.
     */
    public LogFormatter build() {
        return new TextLogFormatter(
                firstNonNull(requestHeadersSanitizer(), HeadersSanitizer.ofText()),
                firstNonNull(responseHeadersSanitizer(), HeadersSanitizer.ofText()),
                firstNonNull(requestTrailersSanitizer(), defaultHeadersSanitizer()),
                firstNonNull(responseTrailersSanitizer(), defaultHeadersSanitizer()),
                firstNonNull(requestContentSanitizer(), defaultSanitizer()),
                firstNonNull(responseContentSanitizer(), defaultSanitizer()),
                includeContext);
    }

    private static <T> BiFunction<? super RequestContext, T, String> defaultSanitizer() {
        return (requestContext, object) -> object.toString();
    }

    private static HeadersSanitizer<String> defaultHeadersSanitizer() {
        return (requestContext, object) -> object.toString();
    }
}
