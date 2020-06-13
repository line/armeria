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

package com.linecorp.armeria.client.unsafe;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.unsafe.PooledHttpData;
import com.linecorp.armeria.common.unsafe.PooledHttpRequest;
import com.linecorp.armeria.common.unsafe.PooledHttpResponse;

/**
 * Decorates an {@link HttpClient}, ensuring {@link HttpData} are all {@link PooledHttpData}.
 */
public abstract class SimplePooledDecoratingHttpClient extends SimpleDecoratingHttpClient
        implements PooledHttpClient {

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    protected SimplePooledDecoratingHttpClient(HttpClient delegate) {
        super(PooledHttpClient.of(delegate));
    }

    @Override
    public final HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        return execute(delegate(), ctx, PooledHttpRequest.of(req));
    }

    @Override
    public final PooledHttpResponse execute(ClientRequestContext ctx, PooledHttpRequest req) throws Exception {
        return PooledHttpResponse.of(execute(delegate(), ctx, req));
    }

    /**
     * Execute the {@code req} with the given {@code ctx}.
     *
     * @see SimpleDecoratingHttpClient#execute(ClientRequestContext, HttpRequest)
     */
    protected abstract HttpResponse execute(
            PooledHttpClient client, ClientRequestContext ctx, PooledHttpRequest req)
            throws Exception;
}
