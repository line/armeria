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
import com.linecorp.armeria.common.unsafe.PooledHttpRequest;
import com.linecorp.armeria.common.unsafe.PooledHttpResponse;
import com.linecorp.armeria.common.util.AbstractUnwrappable;

final class DefaultPooledHttpClient extends AbstractUnwrappable<HttpClient> implements PooledHttpClient {

    DefaultPooledHttpClient(HttpClient delegate) {
        super(delegate);
    }

    @Override
    public PooledHttpResponse execute(ClientRequestContext ctx, PooledHttpRequest req) throws Exception {
        // Always a wrapped non-pooled client, make sure it gets a normal request.
        assert !(unwrap() instanceof PooledHttpClient);
        return PooledHttpResponse.of(unwrap().execute(ctx, req));
    }
}
