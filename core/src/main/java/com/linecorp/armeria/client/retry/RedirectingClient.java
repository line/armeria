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
package com.linecorp.armeria.client.retry;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.UnstableApi;

@UnstableApi
public final class RedirectingClient extends SimpleDecoratingHttpClient {

    private static final RetryConfig<HttpResponse> defaultRedirectConfig =
            RetryConfig.builder(RetryRule.redirect()).maxTotalAttempts(1).build();

    /**
     * Returns a newly-created decorator that redirects automatically when the {@link HttpStatus} of a response
     * is
     * {@link HttpHeaderNames#LOCATION}.
     */
    public static Function<? super HttpClient, RedirectingClient> newDecorator() {
        return delegate -> {
            final RetryingClient retryingClient = RetryingClient.builder(defaultRedirectConfig)
                                                                .build(delegate);
            return new RedirectingClient(retryingClient);
        };
    }

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    RedirectingClient(HttpClient delegate) {
        super(delegate);
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        return unwrap().execute(ctx, req);
    }
}
