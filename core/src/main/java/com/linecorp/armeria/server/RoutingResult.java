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

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.util.StringUtil;

/**
 * The result returned by {@link Route#apply(RoutingContext, boolean)}.
 */
public final class RoutingResult {

    static final int LOWEST_SCORE = Integer.MIN_VALUE;
    static final int HIGHEST_SCORE = Integer.MAX_VALUE;

    private static final RoutingResult EMPTY =
            new RoutingResult(RoutingResultType.NOT_MATCHED, null, null, ImmutableMap.of(), LOWEST_SCORE, null);

    private static final RoutingResult EXCLUDED =
            new RoutingResult(RoutingResultType.NOT_MATCHED, null, null, ImmutableMap.of(), LOWEST_SCORE, null);

    /**
     * The empty {@link RoutingResult} whose {@link #type()} is {@link RoutingResultType#NOT_MATCHED} and
     * {@link #isPresent()} returns {@code false}. It is returned by
     * {@link Route#apply(RoutingContext, boolean)} when the {@link RoutingContext} did not match
     * the conditions in the {@link Route}.
     */
    public static RoutingResult empty() {
        return EMPTY;
    }

    /**
     * The empty {@link RoutingResult} whose {@link #type()} is {@link RoutingResultType#NOT_MATCHED} and
     * {@link #isPresent()} returns {@code false}. It is returned by
     * {@link Route#apply(RoutingContext, boolean)} when the {@link RoutingContext} is acceptable from
     * one of the {@code excludedRoutes}.
     */
    public static RoutingResult excluded() {
        return EXCLUDED;
    }

    /**
     * Returns a new builder.
     */
    public static RoutingResultBuilder builder() {
        return new RoutingResultBuilder();
    }

    /**
     * Returns a new builder, with a hint on the number of path params that will be added.
     */
    static RoutingResultBuilder builderWithExpectedNumParams(int numParams) {
        return new RoutingResultBuilder(numParams);
    }

    private final RoutingResultType type;
    @Nullable
    private final String path;
    @Nullable
    private final String query;

    private final Map<String, String> pathParams;

    @Nullable
    private String decodedPath;

    private final int score;
    @Nullable
    private final MediaType negotiatedResponseMediaType;

    RoutingResult(RoutingResultType type, @Nullable String path, @Nullable String query,
                  Map<String, String> pathParams, int score, @Nullable MediaType negotiatedResponseMediaType) {
        assert type != RoutingResultType.NOT_MATCHED || path == null && query == null && pathParams.isEmpty();

        this.type = type;
        this.path = path;
        this.query = query;
        this.pathParams = ImmutableMap.copyOf(pathParams);
        this.score = score;
        this.negotiatedResponseMediaType = negotiatedResponseMediaType;
    }

    /**
     * Returns the type of this result.
     */
    public RoutingResultType type() {
        return type;
    }

    /**
     * Returns {@code true} if this result is not {@link #empty()}.
     */
    public boolean isPresent() {
        return path != null;
    }

    /**
     * Returns the path mapped by the {@link Route}.
     *
     * @throws IllegalStateException if there's no match
     */
    public String path() {
        ensurePresence();
        return path;
    }

    private void ensurePresence() {
        if (!isPresent()) {
            throw new IllegalStateException("routing unavailable");
        }
    }

    /**
     * Returns the path mapped by the {@link Route}, decoded in UTF-8.
     *
     * @throws IllegalStateException if there's no match
     */
    public String decodedPath() {
        ensurePresence();
        final String decodedPath = this.decodedPath;
        if (decodedPath != null) {
            return decodedPath;
        }

        return this.decodedPath = ArmeriaHttpUtil.decodePath(path);
    }

    /**
     * Returns the query mapped by the {@link Route}.
     *
     * @return the query string, or {@code null} if there is no query part.
     * @throws IllegalStateException if there's no match
     */
    @Nullable
    public String query() {
        ensurePresence();
        return query;
    }

    /**
     * Returns the path parameters extracted by the {@link Route}.
     *
     * @throws IllegalStateException if there's no match
     */
    public Map<String, String> pathParams() {
        ensurePresence();
        return pathParams;
    }

    /**
     * Returns the score of this result.
     * {@link Integer#MAX_VALUE} is the highest score of the result, and {@link Integer#MIN_VALUE} is the
     * lowest score of the result.
     */
    public int score() {
        ensurePresence();
        return score;
    }

    /**
     * Returns whether the score of this result is the highest or not.
     */
    public boolean hasHighestScore() {
        return HIGHEST_SCORE == score();
    }

    /**
     * Returns whether the score of this result is the lowest or not.
     */
    public boolean hasLowestScore() {
        return LOWEST_SCORE == score();
    }

    /**
     * Returns the negotiated producible media type.
     */
    @Nullable
    public MediaType negotiatedResponseMediaType() {
        ensurePresence();
        return negotiatedResponseMediaType;
    }

    @Override
    public String toString() {
        if (isPresent()) {
            String score = StringUtil.toString(this.score);
            if (hasHighestScore()) {
                score += " (highest)";
            } else if (hasLowestScore()) {
                score += " (lowest)";
            }
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("type", type)
                              .add("path", path)
                              .add("query", query)
                              .add("pathParams", pathParams)
                              .add("score", score)
                              .add("negotiatedResponseMediaType", negotiatedResponseMediaType)
                              .toString();
        } else {
            return getClass().getSimpleName() + "{<empty>}";
        }
    }
}
