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

package com.linecorp.armeria.unsafe;

import java.net.URI;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.util.AbstractUnwrappable;

final class DefaultPooledWebClient extends AbstractUnwrappable<WebClient> implements PooledWebClient {

    DefaultPooledWebClient(WebClient delegate) {
        super(delegate);
    }

    @Override
    public PooledHttpResponse execute(HttpRequest req) {
        return new DefaultPooledHttpResponse(delegate().execute(req));
    }

    @Override
    public PooledHttpResponse execute(AggregatedHttpRequest aggregatedReq) {
        return new DefaultPooledHttpResponse(delegate().execute(aggregatedReq));
    }

    @Override
    public Scheme scheme() {
        return delegate().scheme();
    }

    @Override
    public EndpointGroup endpointGroup() {
        return delegate().endpointGroup();
    }

    @Override
    public String absolutePathRef() {
        return delegate().absolutePathRef();
    }

    @Override
    public URI uri() {
        return delegate().uri();
    }

    @Override
    public Class<?> clientType() {
        return delegate().clientType();
    }

    @Override
    public ClientOptions options() {
        return delegate().options();
    }
}
