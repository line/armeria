/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.limit;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * An {@link HttpClient} decorator that limits the concurrent number of active HTTP requests.
 *
 * <p>For example:
 * <pre>{@code
 * WebClientBuilder builder = WebClient.builder(...);
 * builder.decorator(ConcurrencyLimitingClient.newDecorator(16));
 * WebClient client = builder.build();
 * }</pre>
 *
 */
public class ConcurrencyLimitingClient
        extends AbstractConcurrencyLimitingClient<HttpRequest, HttpResponse> implements HttpClient {

    /**
     * Creates a new {@link HttpClient} decorator that limits the concurrent number of active HTTP requests.
     */
    public static Function<? super HttpClient, ConcurrencyLimitingClient>
    newDecorator(int maxConcurrency) {
        validateMaxConcurrency(maxConcurrency);
        return delegate -> new ConcurrencyLimitingClient(delegate, maxConcurrency);
    }

    /**
     * Creates a new {@link HttpClient} decorator that limits the concurrent number of active HTTP requests.
     */
    public static Function<? super HttpClient, ConcurrencyLimitingClient> newDecorator(
            int maxConcurrency, long timeout, TimeUnit unit) {
        validateAll(maxConcurrency, timeout, unit);
        return delegate -> new ConcurrencyLimitingClient(delegate, maxConcurrency, timeout, unit);
    }

    ConcurrencyLimitingClient(HttpClient delegate, int maxConcurrency) {
        super(delegate, maxConcurrency);
    }

    private ConcurrencyLimitingClient(HttpClient delegate,
                                      int maxConcurrency, long timeout, TimeUnit unit) {
        super(delegate, maxConcurrency, timeout, unit);
    }

    @Override
    protected HttpResponse newDeferredResponse(ClientRequestContext ctx,
                                               CompletionStage<HttpResponse> resFuture) throws Exception {
        return HttpResponse.from(resFuture, ctx.eventLoop());
    }
}
