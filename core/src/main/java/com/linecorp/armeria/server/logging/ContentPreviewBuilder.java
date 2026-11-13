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

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.util.Functions;

/**
 * Builds an immutable {@link ContentPreview} configuration.
 */
@UnstableApi
public final class ContentPreviewBuilder {

    private static final BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> DEFAULT_REQUEST_PREVIEW_SANITIZER = Functions.second();
    private static final BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> DEFAULT_RESPONSE_PREVIEW_SANITIZER = Functions.second();

    private final ContentPreviewerFactory contentPreviewerFactory;

    private BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> requestPreviewSanitizer = DEFAULT_REQUEST_PREVIEW_SANITIZER;
    private BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> responsePreviewSanitizer = DEFAULT_RESPONSE_PREVIEW_SANITIZER;

    ContentPreviewBuilder(ContentPreviewerFactory contentPreviewerFactory) {
        this.contentPreviewerFactory = contentPreviewerFactory;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request content preview. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an address, before logging. If unset, will
     * not sanitize request content preview.
     */
    public ContentPreviewBuilder requestPreviewSanitizer(
            BiFunction<? super RequestContext, String,
                    ? extends @Nullable Object> requestPreviewSanitizer) {
        this.requestPreviewSanitizer = requireNonNull(requestPreviewSanitizer, "requestPreviewSanitizer");
        return this;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response content preview. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an address, before logging. If unset, will
     * not sanitize response content preview.
     */
    public ContentPreviewBuilder responsePreviewSanitizer(
            BiFunction<? super RequestContext, String,
                    ? extends @Nullable Object> responsePreviewSanitizer) {
        this.responsePreviewSanitizer = requireNonNull(responsePreviewSanitizer, "responsePreviewSanitizer");
        return this;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request and response content preview. It is common to
     * have the {@link BiFunction} that removes sensitive content, such as an address, before logging.
     * If unset, will not sanitize response content preview.
     * This method is a shortcut for:
     * <pre>{@code
     * builder.requestPreviewSanitizer(previewSanitizer);
     * builder.responsePreviewSanitizer(previewSanitizer);
     * }</pre>
     *
     * @see #requestPreviewSanitizer(BiFunction)
     * @see #responsePreviewSanitizer(BiFunction)
     */
    public ContentPreviewBuilder previewSanitizer(
            BiFunction<? super RequestContext, String,
                    ? extends @Nullable Object> previewSanitizer) {
        requireNonNull(previewSanitizer, "previewSanitizer");
        this.requestPreviewSanitizer = previewSanitizer;
        this.responsePreviewSanitizer = previewSanitizer;
        return this;
    }

    /**
     * Returns a new preview configuration.
     */
    public ContentPreview build() {
        return new ContentPreview(contentPreviewerFactory, requestPreviewSanitizer, responsePreviewSanitizer);
    }
}
