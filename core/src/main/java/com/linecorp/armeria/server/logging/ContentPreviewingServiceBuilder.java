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

package com.linecorp.armeria.server.logging;

import static com.google.common.base.Preconditions.checkState;

import java.nio.charset.Charset;
import java.util.function.Function;

import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.ContentPreviewingDecoratorBuilder;
import com.linecorp.armeria.server.HttpService;

/**
 * Builds a new {@link ContentPreviewingService} or its decorator function.
 */
public final class ContentPreviewingServiceBuilder extends ContentPreviewingDecoratorBuilder {

    @Override
    public ContentPreviewingServiceBuilder contentPreview(int length) {
        return (ContentPreviewingServiceBuilder) super.contentPreview(length);
    }

    @Override
    public ContentPreviewingServiceBuilder contentPreview(int length, Charset defaultCharset) {
        return (ContentPreviewingServiceBuilder) super.contentPreview(length, defaultCharset);
    }

    @Override
    public ContentPreviewingServiceBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        return (ContentPreviewingServiceBuilder) super.contentPreviewerFactory(factory);
    }

    @Override
    public ContentPreviewingServiceBuilder requestContentPreviewerFactory(
            ContentPreviewerFactory requestContentPreviewerFactory) {
        return (ContentPreviewingServiceBuilder)
                super.requestContentPreviewerFactory(requestContentPreviewerFactory);
    }

    @Override
    public ContentPreviewingServiceBuilder responseContentPreviewerFactory(
            ContentPreviewerFactory responseContentPreviewerFactory) {
        return (ContentPreviewingServiceBuilder)
                super.responseContentPreviewerFactory(responseContentPreviewerFactory);
    }

    /**
     * Returns a newly-created {@link ContentPreviewingService} based on the properties of this builder.
     */
    public ContentPreviewingService build(HttpService delegate) {
        checkState(requestContentPreviewerFactory() != null || responseContentPreviewerFactory() != null,
                   "requestContentPreviewerFactory or responseContentPreviewerFactory must be set");
        return new ContentPreviewingService(delegate, requestContentPreviewerFactory(),
                                            responseContentPreviewerFactory());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpService} with a new
     * {@link ContentPreviewingService} based on the properties of this builder.
     */
    public Function<? super HttpService, ContentPreviewingService> newDecorator() {
        return this::build;
    }
}
