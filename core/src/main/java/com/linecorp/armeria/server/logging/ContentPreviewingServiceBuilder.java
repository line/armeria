/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.util.Functions;
import com.linecorp.armeria.server.HttpService;

/**
 * Builds a new {@link ContentPreviewingService}.
 */
public final class ContentPreviewingServiceBuilder {

    private static final BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> DEFAULT_REQUEST_CONTENT_SANITIZER = Functions.second();
    private static final BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> DEFAULT_RESPONSE_CONTENT_SANITIZER = Functions.second();

    private final ContentPreviewerFactory contentPreviewerFactory;

    private BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> requestContentSanitizer = DEFAULT_REQUEST_CONTENT_SANITIZER;
    private BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> responseContentSanitizer = DEFAULT_RESPONSE_CONTENT_SANITIZER;

    ContentPreviewingServiceBuilder(ContentPreviewerFactory contentPreviewerFactory) {
        this.contentPreviewerFactory = contentPreviewerFactory;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request content preview. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an address, before logging. If unset, will
     * not sanitize request content preview.
     */
    public ContentPreviewingServiceBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, String,
                    ? extends @Nullable Object> requestContentSanitizer) {
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        return this;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response content preview. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an address, before logging. If unset, will
     * not sanitize response content preview.
     */
    public ContentPreviewingServiceBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, String,
                    ? extends @Nullable Object> responseContentSanitizer) {
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "responseContentSanitizer");
        return this;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request and response content preview. It is common to
     * have the {@link BiFunction} that removes sensitive content, such as an address, before logging.
     * If unset, will not sanitize response content preview.
     * This method is a shortcut for:
     * <pre>{@code
     * builder.requestContentSanitizer(contentSanitizer);
     * builder.responseContentSanitizer(contentSanitizer);
     * }</pre>
     *
     * @see #requestContentSanitizer(BiFunction)
     * @see #responseContentSanitizer(BiFunction)
     */
    public ContentPreviewingServiceBuilder contentSanitizer(
            BiFunction<? super RequestContext, String,
                    ? extends @Nullable Object> contentSanitizer) {
        requireNonNull(contentSanitizer, "contentSanitizer");
        this.requestContentSanitizer = contentSanitizer;
        this.responseContentSanitizer = contentSanitizer;
        return this;
    }

    /**
     * Returns a newly-created {@link ContentPreviewingService} decorating {@code delegate} based on the
     * properties of this builder.
     */
    public ContentPreviewingService build(HttpService delegate) {
        return new ContentPreviewingService(delegate, contentPreviewerFactory,
                                            requestContentSanitizer, responseContentSanitizer);
    }

    /**
     * Returns a newly-created {@link ContentPreviewingService} decorator based on the properties of this
     * builder.
     */
    public Function<? super HttpService, ContentPreviewingService> newDecorator() {
        return this::build;
    }
}
