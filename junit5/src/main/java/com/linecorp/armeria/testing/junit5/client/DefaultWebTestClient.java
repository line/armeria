/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.testing.junit5.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Scheme;


final class DefaultWebTestClient implements WebTestClient {

    static final WebTestClient DEFAULT = new DefaultWebTestClient(BlockingWebClient.of());

    static final RequestOptions RESPONSE_STREAMING_REQUEST_OPTIONS =
            RequestOptions.builder()
                          .exchangeType(ExchangeType.RESPONSE_STREAMING)
                          .build();

    private final BlockingWebClient delegate;

    DefaultWebTestClient(BlockingWebClient delegate) {
        requireNonNull(delegate, "delegate");
        this.delegate = delegate;
    }

    @Override
    public TestHttpResponse execute(HttpRequest req, RequestOptions requestOptions) {
        requireNonNull(req, "req");
        requireNonNull(requestOptions, "requestOptions");
        return TestHttpResponse.of(delegate.execute(req, requestOptions));
    }

    @Override
    public WebTestClientPreparation prepare() {
        return new WebTestClientPreparation(delegate.prepare());
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
        return WebTestClient.class;
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
