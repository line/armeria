/*
 * Copyright 2018 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.internal.ArmeriaHttpUtil;

/**
 * Builds a {@link PathMappingResult}.
 */
final class PathMappingResultBuilder {

    @Nullable
    private String path;
    @Nullable
    private String query;

    private final ImmutableMap.Builder<String, String> params = ImmutableMap.builder();

    /**
     * Sets the mapped path, encoded as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     */
    PathMappingResultBuilder path(String path) {
        this.path = requireNonNull(path, "path");
        return this;
    }

    /**
     * Sets the specified query.
     */
    PathMappingResultBuilder query(@Nullable String query) {
        this.query = query;
        return this;
    }

    /**
     * Adds a decoded path parameter.
     */
    PathMappingResultBuilder decodedParam(String name, String value) {
        params.put(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds an encoded path parameter, which will be decoded in UTF-8 automatically.
     */
    PathMappingResultBuilder rawParam(String name, String value) {
        params.put(requireNonNull(name, "name"), ArmeriaHttpUtil.decodePath(requireNonNull(value, "value")));
        return this;
    }

    /**
     * Returns a newly-created {@link PathMappingResult}.
     */
    PathMappingResult build() {
        return new PathMappingResult(path, query, params.build());
    }
}
