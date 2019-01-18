/*
 * Copyright 2017 LINE Corporation
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

import static com.linecorp.armeria.server.DefaultPathMappingContext.generateSummary;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

/**
 * A wrapper class of {@link PathMappingContext}. This would be used to override a parameter of an
 * existing {@link PathMappingContext} instance.
 */
class PathMappingContextWrapper implements PathMappingContext {

    private final PathMappingContext delegate;
    @Nullable
    private List<Object> summary;

    PathMappingContextWrapper(PathMappingContext delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    public VirtualHost virtualHost() {
        return delegate().virtualHost();
    }

    @Override
    public String hostname() {
        return delegate().hostname();
    }

    @Override
    public HttpMethod method() {
        return delegate().method();
    }

    @Override
    public String path() {
        return delegate().path();
    }

    @Nullable
    @Override
    public String query() {
        return delegate().query();
    }

    @Nullable
    @Override
    public MediaType consumeType() {
        return delegate().consumeType();
    }

    @Nullable
    @Override
    public List<MediaType> produceTypes() {
        return delegate().produceTypes();
    }

    @Override
    public boolean isCorsPreflight() {
        return delegate().isCorsPreflight();
    }

    @Override
    public List<Object> summary() {
        if (summary == null) {
            summary = generateSummary(this);
        }
        return summary;
    }

    @Override
    public void delayThrowable(Throwable cause) {
        delegate().delayThrowable(cause);
    }

    @Override
    public Optional<Throwable> delayedThrowable() {
        return delegate().delayedThrowable();
    }

    @Override
    public int hashCode() {
        return summary().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PathMappingContext && summary().equals(obj);
    }

    @Override
    public String toString() {
        return summary().toString();
    }

    protected final PathMappingContext delegate() {
        return delegate;
    }
}
