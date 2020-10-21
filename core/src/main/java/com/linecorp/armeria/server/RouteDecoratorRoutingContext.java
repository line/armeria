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
package com.linecorp.armeria.server;

import java.util.List;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;

final class RouteDecoratorRoutingContext implements RoutingContext {

    private final RoutingContext delegate;

    static RoutingContext of(RoutingContext delegate) {
        return new RouteDecoratorRoutingContext(delegate);
    }

    private RouteDecoratorRoutingContext(RoutingContext delegate) {
        this.delegate = delegate;
    }

    @Override
    public VirtualHost virtualHost() {
        return delegate.virtualHost();
    }

    @Override
    public String hostname() {
        return delegate.hostname();
    }

    @Override
    public HttpMethod method() {
        return delegate.method();
    }

    @Override
    public String path() {
        return delegate.path();
    }

    @Override
    public String query() {
        return delegate.query();
    }

    @Override
    public QueryParams params() {
        return delegate.params();
    }

    @Override
    public MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public List<MediaType> acceptTypes() {
        return delegate.acceptTypes();
    }

    @Override
    public RequestHeaders headers() {
        return delegate.headers();
    }

    @Override
    public void deferStatusException(HttpStatusException cause) {
        // Ignore silently.
    }

    @Override
    public HttpStatusException deferredStatusException() {
        return delegate.deferredStatusException();
    }

    @Override
    public boolean isCorsPreflight() {
        return delegate.isCorsPreflight();
    }
}
