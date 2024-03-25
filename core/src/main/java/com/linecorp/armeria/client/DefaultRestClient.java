/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.Scheme;

final class DefaultRestClient implements RestClient {

    static final RestClient DEFAULT = new DefaultRestClient(WebClient.of());

    private final WebClient delegate;

    DefaultRestClient(WebClient delegate) {
        requireNonNull(delegate, "delegate");
        this.delegate = delegate;
    }

    @Override
    public RestClientPreparation path(HttpMethod method, String path) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        return new RestClientPreparation(delegate, method, path);
    }

    @Override
    public Scheme scheme() {
        return delegate.scheme();
    }

    @Override
    public EndpointGroup endpointGroup() {
        return delegate.endpointGroup();
    }

    @Override
    public String absolutePathRef() {
        return delegate.absolutePathRef();
    }

    @Override
    public URI uri() {
        return delegate.uri();
    }

    @Override
    public Class<?> clientType() {
        return RestClient.class;
    }

    @Override
    public ClientOptions options() {
        return delegate.options();
    }

    @Override
    public HttpClient unwrap() {
        return delegate.unwrap();
    }

    @Override
    public Object unwrapAll() {
        return delegate.unwrapAll();
    }
}
