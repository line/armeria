/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.DefaultPathMappingContext.generateSummary;

import java.util.List;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

/**
 * A wrapper class of {@link PathMappingContext}. This would be used to override a parameter of the
 * existing {@link PathMappingContext} instance.
 */
class PathMappingContextWrapper implements PathMappingContext {

    private final PathMappingContext delegate;
    private String summary;

    PathMappingContextWrapper(PathMappingContext delegate) {
        this.delegate = delegate;
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

    @Override
    public List<MediaType> produceTypes() {
        return delegate().produceTypes();
    }

    @Override
    public String summary() {
        if (summary == null) {
            summary = generateSummary(this);
        }
        return summary;
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
        return summary();
    }

    protected final PathMappingContext delegate() {
        return delegate;
    }
}
