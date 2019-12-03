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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

class RoutingContextWrapper implements RoutingContext {

    private final RoutingContext delegate;
    private final List<Object> summary;

    RoutingContextWrapper(RoutingContext delegate) {
        this.delegate = delegate;
        this.summary = DefaultRoutingContext.generateSummary(this);
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

    @Nullable
    @Override
    public String query() {
        return delegate.query();
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
    public List<Object> summary() {
        return delegate.summary();
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
    public boolean isCorsPreflight() {
        return delegate.isCorsPreflight();
    }

    @Override
    public int hashCode() {
        return summary().hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof RoutingContext &&
               (this == obj || summary().equals(((RoutingContext) obj).summary()));
    }

    @Override
    public String toString() {
        return summary().toString();
    }
}
