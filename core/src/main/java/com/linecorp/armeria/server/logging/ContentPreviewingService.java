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

import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.logging.ContentPreviewerConfigurator;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * Decorates an {@link HttpService} to preview the content of {@link Request}s and {@link Response}s.
 */
public final class ContentPreviewingService extends SimpleDecoratingHttpService {

    /**
     * Returns a newly created {@link ContentPreviewingServiceBuilder}.
     */
    public static ContentPreviewingServiceBuilder builder() {
        return new ContentPreviewingServiceBuilder();
    }

    /**
     * Creates a new {@link ContentPreviewingService} decorator with the specified
     * {@code requestContentPreviewerFactory} and {@code responseContentPreviewerFactory}.
     */
    public static Function<? super HttpService, ContentPreviewingService> newDecorator(
            ContentPreviewerFactory requestContentPreviewerFactory,
            ContentPreviewerFactory responseContentPreviewerFactory) {
        return builder().requestContentPreviewerFactory(requestContentPreviewerFactory)
                        .responseContentPreviewerFactory(responseContentPreviewerFactory)
                        .newDecorator();
    }

    private final ContentPreviewerConfigurator configurator;

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    ContentPreviewingService(HttpService delegate,
                             @Nullable ContentPreviewerFactory requestContentPreviewerFactory,
                             @Nullable ContentPreviewerFactory responseContentPreviewerFactory) {
        super(delegate);
        configurator = new ContentPreviewerConfigurator(
                requestContentPreviewerFactory, responseContentPreviewerFactory);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        req = configurator.maybeSetUpRequestContentPreviewer(ctx, req);
        final HttpResponse res = delegate().serve(ctx, req);
        return configurator.maybeSetUpResponseContentPreviewer(ctx, res);
    }
}
