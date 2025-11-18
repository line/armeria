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
package com.linecorp.armeria.client;

import java.net.URI;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;

/**
 * Default {@link ClientBuilderParams} implementation.
 */
final class DefaultClientBuilderParams implements ClientBuilderParams {

    private final Scheme scheme;
    private final EndpointGroup endpointGroup;
    private final String absolutePathRef;
    private final URI uri;
    private final Class<?> type;
    private final ClientOptions options;

    DefaultClientBuilderParams(Scheme scheme, EndpointGroup endpointGroup, String absolutePathRef,
                               URI uri, Class<?> type, ClientOptions options) {
        this.scheme = options.factory().validateScheme(scheme);
        this.endpointGroup = endpointGroup;
        this.absolutePathRef = absolutePathRef;
        this.uri = uri;
        this.type = type;
        this.options = options;
    }

    @Override
    public Scheme scheme() {
        return scheme;
    }

    @Override
    public EndpointGroup endpointGroup() {
        return endpointGroup;
    }

    @Override
    public String absolutePathRef() {
        return absolutePathRef;
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public Class<?> clientType() {
        return type;
    }

    @Override
    public ClientOptions options() {
        return options;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("scheme", scheme)
                          .add("endpointGroup", endpointGroup)
                          .add("absolutePathRef", absolutePathRef)
                          .add("type", type)
                          .add("options", options).toString();
    }
}
