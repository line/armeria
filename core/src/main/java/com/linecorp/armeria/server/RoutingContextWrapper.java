/*
 * Copyright 2019 LINE Corporation
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
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

class RoutingContextWrapper implements RoutingContext {

    private final RoutingContext delegate;

    private final int hashcode;

    RoutingContextWrapper(RoutingContext delegate) {
        this.delegate = delegate;
        hashcode = DefaultRoutingContext.hashCode(this);
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
    public RequestTarget requestTarget() {
        return delegate.requestTarget();
    }

    @Override
    public final String path() {
        return RoutingContext.super.path();
    }

    @Nullable
    @Override
    public final String query() {
        return RoutingContext.super.query();
    }

    @Override
    public QueryParams params() {
        return delegate.params();
    }

    @Nullable
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
    public RoutingStatus status() {
        return delegate.status();
    }

    @Override
    public SessionProtocol sessionProtocol() {
        return delegate.sessionProtocol();
    }

    @Override
    public void deferStatusException(HttpStatusException cause) {
        delegate.deferStatusException(cause);
    }

    @Override
    public HttpStatusException deferredStatusException() {
        return delegate.deferredStatusException();
    }

    @Override
    public RoutingContext withPath(String path) {
        return delegate.withPath(path);
    }

    @Override
    @Deprecated
    public boolean isCorsPreflight() {
        return delegate.isCorsPreflight();
    }

    @Override
    public boolean requiresMatchingParamsPredicates() {
        return delegate.requiresMatchingParamsPredicates();
    }

    @Override
    public boolean requiresMatchingHeadersPredicates() {
        return delegate.requiresMatchingHeadersPredicates();
    }

    @Override
    public boolean hasResult() {
        return delegate.hasResult();
    }

    @Override
    public void setResult(Routed<ServiceConfig> result) {
        delegate.setResult(result);
    }

    @Override
    public Routed<ServiceConfig> result() {
        return delegate.result();
    }

    @Override
    public int hashCode() {
        return hashcode;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return DefaultRoutingContext.equals(this, obj);
    }

    @Override
    public String toString() {
        return DefaultRoutingContext.toString(this);
    }
}
