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

import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.internal.common.logging.ContentPreviewerConfigurator;

/**
 * Decorates an {@link HttpClient} to preview the content of {@link Request}s and {@link Response}s.
 *
 * <p>Note that this decorator just sets {@link RequestLog#requestContentPreview()} and
 * {@link RequestLog#responseContentPreview()}. You can get the previews using {@link RequestLogAccess}.
 *
 * <pre>{@code
 * RequestLogAccess logAccess = ctx.log();
 * logAccess.whenComplete().thenApply(log -> {
 *     // Call log.requestContentPreview() and log.responseContentPreview() to use them.
 *     ...
 * });
 * }</pre>
 */
public final class ContentPreviewingClient extends SimpleDecoratingHttpClient {

    /**
     * Returns a newly created {@link ContentPreviewingClientBuilder}.
     */
    public static ContentPreviewingClientBuilder builder() {
        return new ContentPreviewingClientBuilder();
    }

    /**
     * Creates a new {@link ContentPreviewingClient} decorator with the specified
     * {@code requestContentPreviewerFactory} and {@code responseContentPreviewerFactory}.
     */
    public static Function<? super HttpClient, ContentPreviewingClient> newDecorator(
            ContentPreviewerFactory requestContentPreviewerFactory,
            ContentPreviewerFactory responseContentPreviewerFactory) {
        return builder().requestContentPreviewerFactory(requestContentPreviewerFactory)
                        .responseContentPreviewerFactory(responseContentPreviewerFactory)
                        .newDecorator();
    }

    @Nullable
    private final ContentPreviewerFactory responseContentPreviewerFactory;
    private final ContentPreviewerConfigurator configurator;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    ContentPreviewingClient(HttpClient delegate,
                            @Nullable ContentPreviewerFactory requestContentPreviewerFactory,
                            @Nullable ContentPreviewerFactory responseContentPreviewerFactory) {
        super(delegate);
        this.responseContentPreviewerFactory = responseContentPreviewerFactory;
        configurator = new ContentPreviewerConfigurator(
                requestContentPreviewerFactory, responseContentPreviewerFactory);
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        req = configurator.maybeSetUpRequestContentPreviewer(ctx, req);
        if (responseContentPreviewerFactory == null) {
            return delegate().execute(ctx, req);
        }
        ctx.logBuilder().deferResponseContentPreview();
        final HttpResponse res = delegate().execute(ctx, req);
        return configurator.maybeSetUpResponseContentPreviewer(ctx, res);
    }
}
