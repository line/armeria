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

import static java.util.Objects.requireNonNull;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.MediaType;

/**
 * The result returned by {@link Route#apply(RoutingContext)}.
 */
public class RouteResult {

    static final int LOWEST_SCORE = Integer.MIN_VALUE;
    static final int HIGHEST_SCORE = Integer.MAX_VALUE;

    private static final RouteResult EMPTY =
            new RouteResult(PathMappingResult.empty(), LOWEST_SCORE, null);

    /**
     * The {@link RouteResult} whose {@link #isPresent()} returns {@code false}. It is returned by
     * {@link Route#apply(RoutingContext)} when the {@link RoutingContext} did not match the
     * conditions in the {@link Route}.
     */
    public static RouteResult empty() {
        return EMPTY;
    }

    /**
     * Returns a new builder.
     */
    public static RouteResultBuilder builder() {
        return new RouteResultBuilder();
    }

    private final PathMappingResult pathMappingResult;

    private final int score;
    @Nullable
    private final MediaType negotiatedResponseMediaType;

    RouteResult(PathMappingResult pathMappingResult, int score,
                @Nullable MediaType negotiatedResponseMediaType) {
        this.pathMappingResult = requireNonNull(pathMappingResult, "pathMappingResult");
        this.score = score;
        this.negotiatedResponseMediaType = negotiatedResponseMediaType;
    }

    /**
     * Returns {@code true} if this result is not {@link #empty()}.
     */
    public boolean isPresent() {
        return pathMappingResult.isPresent();
    }

    /**
     * Returns the path mapped by the {@link Route}.
     *
     * @throws IllegalStateException if there's no match
     */
    public String path() {
        return pathMappingResult.path();
    }

    /**
     * Returns the path mapped by the {@link Route}, decoded in UTF-8.
     *
     * @throws IllegalStateException if there's no match
     */
    public String decodedPath() {
        return pathMappingResult.decodedPath();
    }

    /**
     * Returns the query mapped by the {@link Route}.
     *
     * @return the query string. {@code null} If there is no query part.
     * @throws IllegalStateException if there's no match
     */
    @Nullable
    public String query() {
        return pathMappingResult.query();
    }

    /**
     * Returns the path parameters extracted by the {@link Route}.
     *
     * @throws IllegalStateException if there's no match
     */
    public Map<String, String> pathParams() {
        return pathMappingResult.pathParams();
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

    private void ensurePresence() {
        if (!pathMappingResult.isPresent()) {
            throw new IllegalStateException("mapping unavailable");
        }
    }

    @Override
    public String toString() {
        if (isPresent()) {
            String score = String.valueOf(this.score);
            if (hasHighestScore()) {
                score += " (highest)";
            } else if (hasLowestScore()) {
                score += " (lowest)";
            }
            return MoreObjects.toStringHelper(this)
                              .add("pathMappingResult", pathMappingResult)
                              .add("score", score)
                              .add("negotiatedResponseMediaType", negotiatedResponseMediaType)
                              .toString();
        } else {
            return getClass().getSimpleName() + "{<empty>}";
        }
    }
}
