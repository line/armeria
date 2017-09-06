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

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.DeferredHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * A {@link Client} decorator that limits the concurrent number of active HTTP requests.
 *
 * <p>For example:
 * <pre>{@code
 * ClientBuilder builder = new ClientBuilder(...);
 * builder.decorator(HttpRequest.class, HttpResponse.class, ConcurrencyLimitingHttpClient.newDecorator(16));
 * client = builder.build(...);
 * }</pre>
 *
 */
public final class ConcurrencyLimitingHttpClient extends ConcurrencyLimitingClient<HttpRequest, HttpResponse> {

    /**
     * Creates a new {@link Client} decorator that limits the concurrent number of active HTTP requests.
     */
    public static Function<Client<HttpRequest, HttpResponse>, ConcurrencyLimitingHttpClient>
    newDecorator(int maxConcurrency) {
        validateMaxConcurrency(maxConcurrency);
        return delegate -> new ConcurrencyLimitingHttpClient(delegate, maxConcurrency);
    }

    /**
     * Creates a new {@link Client} decorator that limits the concurrent number of active HTTP requests.
     */
    public static Function<Client<HttpRequest, HttpResponse>, ConcurrencyLimitingHttpClient> newDecorator(
            int maxConcurrency, long timeout, TimeUnit unit) {
        validateAll(maxConcurrency, timeout, unit);
        return delegate -> new ConcurrencyLimitingHttpClient(delegate, maxConcurrency, timeout, unit);
    }

    private ConcurrencyLimitingHttpClient(Client<HttpRequest, HttpResponse> delegate, int maxConcurrency) {
        super(delegate, maxConcurrency);
    }

    private ConcurrencyLimitingHttpClient(Client<HttpRequest, HttpResponse> delegate,
                                          int maxConcurrency, long timeout, TimeUnit unit) {
        super(delegate, maxConcurrency, timeout, unit);
    }

    @Override
    protected Deferred<HttpResponse> defer(ClientRequestContext ctx, HttpRequest req) throws Exception {
        return new Deferred<HttpResponse>() {
            private final DeferredHttpResponse res = new DeferredHttpResponse();

            @Override
            public HttpResponse response() {
                return res;
            }

            @Override
            public void delegate(HttpResponse response) {
                res.delegate(response);
            }

            @Override
            public void close(Throwable cause) {
                res.close(cause);
            }
        };
    }
}
