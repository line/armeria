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
public final class PathMappingResultBuilder {

    private final String path;
    @Nullable
    private final String query;
    private final ImmutableMap.Builder<String, String> params = ImmutableMap.builder();
    private int score = PathMappingResult.LOWEST_SCORE;

    /**
     * Creates a new instance.
     *
     * @param path the mapped path, encoded as defined in
     *             <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>
     */
    public PathMappingResultBuilder(String path) {
        this(path, null);
    }

    /**
     * Creates a new instance.
     *
     * @param path the encoded mapped path
     * @param query the encoded query string or {@code null}
     */
    public PathMappingResultBuilder(String path, @Nullable String query) {
        this.path = requireNonNull(path, "path");
        this.query = query;
    }

    /**
     * Sets the score of the result.
     */
    public PathMappingResultBuilder score(int score) {
        this.score = score;
        return this;
    }

    /**
     * Adds a decoded path parameter.
     */
    public PathMappingResultBuilder decodedParam(String name, String value) {
        params.put(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds an encoded path parameter, which will be decoded in UTF-8 automatically.
     */
    public PathMappingResultBuilder rawParam(String name, String value) {
        params.put(requireNonNull(name, "name"), ArmeriaHttpUtil.decodePath(requireNonNull(value, "value")));
        return this;
    }

    /**
     * Returns a newly-created {@link PathMappingResult}.
     */
    public PathMappingResult build() {
        return new PathMappingResult(path, query, params.build(), score);
    }
}
