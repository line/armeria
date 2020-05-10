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

package com.linecorp.armeria.server.unsafe;

import com.linecorp.armeria.common.unsafe.PooledHttpRequest;
import com.linecorp.armeria.common.unsafe.PooledHttpResponse;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

final class DefaultPooledHttpService extends AbstractUnwrappable<HttpService> implements PooledHttpService {

    DefaultPooledHttpService(HttpService delegate) {
        super(delegate);
    }

    @Override
    public PooledHttpResponse serve(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        // Always a wrapped non-pooled service, make sure it gets a normal request.
        assert !(delegate() instanceof PooledHttpService);
        return PooledHttpResponse.of(delegate().serve(ctx, req.toUnpooled()));
    }
}
