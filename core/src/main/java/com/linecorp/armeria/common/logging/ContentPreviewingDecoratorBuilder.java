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
package com.linecorp.armeria.common.logging;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;

import javax.annotation.Nullable;

import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

/**
 * Builds a new content previewing decorator or its decorator function.
 */
public abstract class ContentPreviewingDecoratorBuilder {

    @Nullable
    private ContentPreviewerFactory requestContentPreviewerFactory;

    @Nullable
    private ContentPreviewerFactory responseContentPreviewerFactory;

    /**
     * Creates a new instance.
     */
    protected ContentPreviewingDecoratorBuilder() {}

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for a request and a response.
     * The previewer is enabled only if the content type of a request/response meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview.
     */
    public ContentPreviewingDecoratorBuilder contentPreview(int length) {
        return contentPreview(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for a request and a response.
     * The previewer is enabled only if the content type of a request/response meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    public ContentPreviewingDecoratorBuilder contentPreview(int length, Charset defaultCharset) {
        requireNonNull(defaultCharset, "defaultCharset");
        return contentPreviewerFactory(ContentPreviewerFactory.ofText(length, defaultCharset));
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a request and a response.
     */
    public ContentPreviewingDecoratorBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        requireNonNull(factory, "factory");
        checkState(requestContentPreviewerFactory == null, "requestContentPreviewerFactory is already set.");
        checkState(responseContentPreviewerFactory == null, "responseContentPreviewerFactory is already set.");
        requestContentPreviewerFactory = factory;
        responseContentPreviewerFactory = factory;
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a request.
     */
    public ContentPreviewingDecoratorBuilder requestContentPreviewerFactory(
            ContentPreviewerFactory requestContentPreviewerFactory) {
        requireNonNull(requestContentPreviewerFactory, "requestContentPreviewerFactory");
        this.requestContentPreviewerFactory = requestContentPreviewerFactory;
        return this;
    }

    @Nullable
    protected ContentPreviewerFactory requestContentPreviewerFactory() {
        return requestContentPreviewerFactory;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a response.
     */
    public ContentPreviewingDecoratorBuilder responseContentPreviewerFactory(
            ContentPreviewerFactory responseContentPreviewerFactory) {
        requireNonNull(responseContentPreviewerFactory, "responseContentPreviewerFactory");
        this.responseContentPreviewerFactory = responseContentPreviewerFactory;
        return this;
    }

    @Nullable
    protected ContentPreviewerFactory responseContentPreviewerFactory() {
        return responseContentPreviewerFactory;
    }
}
