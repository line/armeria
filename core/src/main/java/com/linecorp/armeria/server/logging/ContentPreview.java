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

package com.linecorp.armeria.server.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.util.Functions;

/**
 * An immutable configuration for server request and response content previews.
 */
@UnstableApi
public final class ContentPreview {
    private static final ContentPreview DISABLED =
            new ContentPreview(null, Functions.second(), Functions.second());

    /**
     * Returns a configuration that uses the specified factory without sanitization.
     */
    public static ContentPreview of(ContentPreviewerFactory factory) {
        return builder(factory).build();
    }

    /**
     * Returns a new builder for the specified factory.
     */
    public static ContentPreviewBuilder builder(ContentPreviewerFactory factory) {
        return new ContentPreviewBuilder(requireNonNull(factory, "factory"));
    }

    /**
     * Returns a configuration that disables previews.
     */
    public static ContentPreview disabled() {
        return DISABLED;
    }

    @Nullable
    private final ContentPreviewerFactory factory;
    private final BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> requestPreviewSanitizer;
    private final BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> responsePreviewSanitizer;

    ContentPreview(@Nullable ContentPreviewerFactory factory,
                   BiFunction<? super RequestContext, String,
                           ? extends @Nullable Object> requestPreviewSanitizer,
                   BiFunction<? super RequestContext, String,
                           ? extends @Nullable Object> responsePreviewSanitizer) {
        this.factory = factory;
        this.requestPreviewSanitizer = requestPreviewSanitizer;
        this.responsePreviewSanitizer = responsePreviewSanitizer;
    }

    /**
     * Returns the previewer factory, or {@code null} if previews are disabled.
     */
    @Nullable
    public ContentPreviewerFactory factory() {
        return factory;
    }

    /**
     * Returns the request preview sanitizer.
     */
    public BiFunction<? super RequestContext, String, ? extends @Nullable Object> requestPreviewSanitizer() {
        return requestPreviewSanitizer;
    }

    /**
     * Returns the response preview sanitizer.
     */
    public BiFunction<? super RequestContext, String, ? extends @Nullable Object> responsePreviewSanitizer() {
        return responsePreviewSanitizer;
    }
}
