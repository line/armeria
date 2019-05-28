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

import static com.linecorp.armeria.server.RoutingResult.LOWEST_SCORE;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

/**
 * Builds a new {@link RoutingResult}.
 */
public final class RoutingResultBuilder {

    private static final RoutingResultBuilder immutableBuilder = new RoutingResultBuilder(true);

    private final boolean isImmutable;

    @Nullable
    private String path;

    @Nullable
    private String query;

    private final ImmutableMap.Builder<String, String> pathParams = ImmutableMap.builder();

    private int score = LOWEST_SCORE;

    @Nullable
    private MediaType negotiatedResponseMediaType;

    /**
     * Returns the immutable {@link RoutingResultBuilder}.
     */
    static RoutingResultBuilder immutable() {
        return immutableBuilder;
    }

    /**
     * Creates a new instance.
     */
    RoutingResultBuilder(boolean isImmutable) {
        this.isImmutable = isImmutable;
    }

    /**
     * Returns {@code true} if this result is not immutable.
     */
    boolean isImmutable() {
        return isImmutable;
    }

    /**
     * Sets the mapped path, encoded as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     */
    public RoutingResultBuilder path(String path) {
        ensureMutable();
        this.path = requireNonNull(path, "path");
        return this;
    }

    private void ensureMutable() {
        if (isImmutable) {
            throw new IllegalStateException("Cannot set to the immutable builder.");
        }
    }

    /**
     * Sets the specified query.
     */
    public RoutingResultBuilder query(@Nullable String query) {
        ensureMutable();
        this.query = query;
        return this;
    }

    /**
     * Adds a decoded path parameter.
     */
    public RoutingResultBuilder decodedParam(String name, String value) {
        ensureMutable();
        pathParams.put(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds an encoded path parameter, which will be decoded in UTF-8 automatically.
     */
    public RoutingResultBuilder rawParam(String name, String value) {
        ensureMutable();
        pathParams.put(requireNonNull(name, "name"),
                       ArmeriaHttpUtil.decodePath(requireNonNull(value, "value")));
        return this;
    }

    /**
     * Sets the score.
     */
    public RoutingResultBuilder score(int score) {
        ensureMutable();
        this.score = score;
        return this;
    }

    /**
     * Sets the negotiated producible {@link MediaType}.
     */
    public RoutingResultBuilder negotiatedResponseMediaType(MediaType negotiatedResponseMediaType) {
        ensureMutable();
        this.negotiatedResponseMediaType = requireNonNull(negotiatedResponseMediaType,
                                                          "negotiatedResponseMediaType");
        return this;
    }

    /**
     * Returns a newly-created {@link RoutingResult}.
     */
    public RoutingResult build() {
        if (isImmutable || path == null) {
            return RoutingResult.empty();
        }

        return new RoutingResult(path, query, pathParams.build(), score, negotiatedResponseMediaType);
    }
}
