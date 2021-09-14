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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.server.RoutingResult.LOWEST_SCORE;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

/**
 * Builds a new {@link RoutingResult}.
 */
public final class RoutingResultBuilder {

    private RoutingResultType type = RoutingResultType.MATCHED;

    @Nullable
    private String path;

    @Nullable
    private String query;

    @Nullable
    private ImmutableMap.Builder<String, String> pathParams;

    private int score = LOWEST_SCORE;

    @Nullable
    private MediaType negotiatedResponseMediaType;

    RoutingResultBuilder() {}

    RoutingResultBuilder(int expectedNumParams) {
        pathParams = ImmutableMap.builderWithExpectedSize(expectedNumParams);
    }

    /**
     * Sets the result type.
     *
     * @param type {@link RoutingResultType#MATCHED} or {@link RoutingResultType#CORS_PREFLIGHT}.
     */
    public RoutingResultBuilder type(RoutingResultType type) {
        requireNonNull(type, "type");
        checkArgument(type != RoutingResultType.NOT_MATCHED,
                      "type: %s (expected: %s or %s)",
                      type, RoutingResultType.MATCHED, RoutingResultType.CORS_PREFLIGHT);
        this.type = type;
        return this;
    }

    /**
     * Sets the mapped path, encoded as defined in <a href="https://datatracker.ietf.org/doc/rfc3986/">RFC3986</a>.
     */
    public RoutingResultBuilder path(String path) {
        this.path = requireNonNull(path, "path");
        return this;
    }

    /**
     * Sets the specified query.
     */
    public RoutingResultBuilder query(@Nullable String query) {
        this.query = query;
        return this;
    }

    /**
     * Adds a decoded path parameter.
     */
    public RoutingResultBuilder decodedParam(String name, String value) {
        pathParams().put(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds an encoded path parameter, which will be decoded in UTF-8 automatically.
     */
    public RoutingResultBuilder rawParam(String name, String value) {
        pathParams().put(requireNonNull(name, "name"),
                         ArmeriaHttpUtil.decodePath(requireNonNull(value, "value")));
        return this;
    }

    /**
     * Sets the score.
     */
    public RoutingResultBuilder score(int score) {
        this.score = score;
        return this;
    }

    /**
     * Sets the negotiated producible {@link MediaType}.
     */
    public RoutingResultBuilder negotiatedResponseMediaType(MediaType negotiatedResponseMediaType) {
        this.negotiatedResponseMediaType = requireNonNull(negotiatedResponseMediaType,
                                                          "negotiatedResponseMediaType");
        return this;
    }

    /**
     * Returns a newly-created {@link RoutingResult}.
     */
    public RoutingResult build() {
        if (path == null) {
            return RoutingResult.empty();
        }

        return new RoutingResult(type, path, query,
                                 pathParams != null ? pathParams.build() : ImmutableMap.of(),
                                 score, negotiatedResponseMediaType);
    }

    private ImmutableMap.Builder<String, String> pathParams() {
        if (pathParams != null) {
            return pathParams;
        }
        return pathParams = ImmutableMap.builder();
    }
}
