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

package com.linecorp.armeria.unsafe.server;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.unsafe.common.PooledHttpData;
import com.linecorp.armeria.unsafe.common.PooledHttpRequest;
import com.linecorp.armeria.unsafe.common.PooledHttpResponse;

/**
 * An {@link HttpService} that decorates another {@link HttpService} publishing {@link PooledHttpData}.
 *
 * @see SimpleDecoratingHttpService
 * @see PooledHttpData
 */
public abstract class SimplePooledDecoratingHttpService extends SimpleDecoratingHttpService
        implements PooledHttpService {

    private final PooledHttpService pooledDelegate;

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    protected SimplePooledDecoratingHttpService(HttpService delegate) {
        super(delegate);
        pooledDelegate = PooledHttpService.of(delegate);
    }

    @Override
    public final HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return serve(ctx, PooledHttpRequest.of(req), pooledDelegate);
    }

    @Override
    public PooledHttpResponse serve(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        return PooledHttpResponse.of(serve(ctx, req, delegate()));
    }

    /**
     * Execute the {@code req} with the given {@code ctx}.
     *
     * @see SimpleDecoratingHttpService#serve(ServiceRequestContext, HttpRequest)
     */
    protected abstract HttpResponse serve(
            ServiceRequestContext ctx, PooledHttpRequest req, PooledHttpService service)
            throws Exception;
}
