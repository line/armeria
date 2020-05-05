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

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.unsafe.common.PooledHttpData;
import com.linecorp.armeria.unsafe.common.PooledHttpRequest;
import com.linecorp.armeria.unsafe.common.PooledHttpResponse;

/**
 * An HTTP/2 {@link Service} which publishes {@link PooledHttpData}.
 */
public interface PooledHttpService extends HttpService, Unwrappable {

    /**
     * Creates a {@link PooledHttpService} that delegates to the provided {@link HttpClient} for issuing
     * requests.
     */
    static PooledHttpService of(HttpService delegate) {
        if (delegate instanceof PooledHttpService) {
            return (PooledHttpService) delegate;
        }
        return new DefaultPooledHttpService(delegate);
    }

    @Override
    default HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return serve(ctx, PooledHttpRequest.of(req));
    }

    /**
     * Serves an incoming {@link PooledHttpRequest}.
     *
     * @param ctx the context of the received {@link PooledHttpRequest}
     * @param req the received {@link PooledHttpRequest}
     *
     * @return the {@link PooledHttpResponse}
     */
    PooledHttpResponse serve(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception;

    /**
     * Returns the unpooled delegate of this {@link PooledHttpService}.
     */
    default HttpService toUnpooled() {
        final HttpService unpooled = as(HttpService.class);
        assert unpooled != null;
        return unpooled;
    }
}
