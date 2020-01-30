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

package com.linecorp.armeria.client.logging;

import static com.google.common.base.Preconditions.checkState;

import java.nio.charset.Charset;
import java.util.function.Function;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.ContentPreviewingDecoratorBuilder;

/**
 * Builds a new {@link ContentPreviewingClient} or its decorator function.
 */
public final class ContentPreviewingClientBuilder extends ContentPreviewingDecoratorBuilder {

    @Override
    public ContentPreviewingClientBuilder contentPreview(int length) {
        return (ContentPreviewingClientBuilder) super.contentPreview(length);
    }

    @Override
    public ContentPreviewingClientBuilder contentPreview(int length, Charset defaultCharset) {
        return (ContentPreviewingClientBuilder) super.contentPreview(length, defaultCharset);
    }

    @Override
    public ContentPreviewingClientBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        return (ContentPreviewingClientBuilder) super.contentPreviewerFactory(factory);
    }

    @Override
    public ContentPreviewingClientBuilder requestContentPreviewerFactory(
            ContentPreviewerFactory requestContentPreviewerFactory) {
        return (ContentPreviewingClientBuilder)
                super.requestContentPreviewerFactory(requestContentPreviewerFactory);
    }

    @Override
    public ContentPreviewingClientBuilder responseContentPreviewerFactory(
            ContentPreviewerFactory responseContentPreviewerFactory) {
        return (ContentPreviewingClientBuilder)
                super.responseContentPreviewerFactory(responseContentPreviewerFactory);
    }

    /**
     * Returns a newly-created {@link ContentPreviewingClient} based on the properties of this builder.
     */
    public ContentPreviewingClient build(HttpClient delegate) {
        checkState(requestContentPreviewerFactory() != null || responseContentPreviewerFactory() != null,
                   "requestContentPreviewerFactory or responseContentPreviewerFactory must be set.");
        return new ContentPreviewingClient(delegate, requestContentPreviewerFactory(),
                                           responseContentPreviewerFactory());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpClient} with a new
     * {@link ContentPreviewingClient} based on the properties of this builder.
     */
    public Function<? super HttpClient, ContentPreviewingClient> newDecorator() {
        return this::build;
    }
}

