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

import static java.util.Objects.requireNonNull;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

/**
 * The result returned by {@link PathMapping#apply(PathMappingContext)}.
 */
public final class PathMappingResult {

    static final int LOWEST_SCORE = Integer.MIN_VALUE;
    static final int HIGHEST_SCORE = Integer.MAX_VALUE;

    private static final PathMappingResult EMPTY =
            new PathMappingResult(null, null, ImmutableMap.of(), LOWEST_SCORE);

    /**
     * The {@link PathMappingResult} whose {@link #isPresent()} returns {@code false}. It is returned by
     * {@link PathMapping#apply(PathMappingContext)} when the specified path did not match.
     */
    public static PathMappingResult empty() {
        return EMPTY;
    }

    /**
     * Creates a new instance with the specified {@code path}, without a query or any path parameters.
     */
    public static PathMappingResult of(String path) {
        return of(path, null);
    }

    /**
     * Creates a new instance with the specified {@code path} and {@code query}, without any path parameters.
     */
    public static PathMappingResult of(String path, @Nullable String query) {
        return of(path, query, ImmutableMap.of());
    }

    /**
     * Creates a new instance with the specified {@code path}, {@code query} and the extracted path parameters.
     */
    public static PathMappingResult of(String path, @Nullable String query, Map<String, String> rawPathParams) {
        return of(path, query, rawPathParams, LOWEST_SCORE);
    }

    /**
     * Creates a new instance with the specified {@code path}, {@code query}, the extracted path parameters
     * and the score.
     */
    public static PathMappingResult of(String path, @Nullable String query,
                                       Map<String, String> rawPathParams, int score) {
        requireNonNull(path, "path");
        requireNonNull(rawPathParams, "rawPathParams");
        return new PathMappingResult(path, query, rawPathParams, score);
    }

    @Nullable
    private final String path;
    @Nullable
    private final String query;
    @Nullable
    private final Map<String, String> pathParams;

    private int score;
    @Nullable
    private MediaType negotiatedResponseMediaType;
    @Nullable
    private String decodedPath;

    PathMappingResult(@Nullable String path, @Nullable String query,
                      Map<String, String> pathParams, int score) {
        assert path != null || query == null && pathParams.isEmpty();

        this.path = path;
        this.query = query;
        this.pathParams = ImmutableMap.copyOf(pathParams);
        this.score = score;
    }

    /**
     * Returns {@code true} if this result is not {@link #empty()}.
     */
    public boolean isPresent() {
        return path != null;
    }

    /**
     * Returns the path mapped by the {@link PathMapping}.
     *
     * @throws IllegalStateException if there's no match
     */
    public String path() {
        ensurePresence();
        return path;
    }

    /**
     * Returns the path mapped by the {@link PathMapping}, decoded in UTF-8.
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
     * Returns the query mapped by the {@link PathMapping}.
     *
     * @return the query string. {@code null} If there is no query part.
     * @throws IllegalStateException if there's no match
     */
    @Nullable
    public String query() {
        ensurePresence();
        return query;
    }

    /**
     * Returns the path parameters extracted by the {@link PathMapping}.
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
     * Sets the new score of this result.
     */
    void setScore(int score) {
        ensurePresence();
        this.score = score;
    }

    /**
     * Returns the negotiated producible media type.
     */
    @Nullable
    public MediaType negotiatedResponseMediaType() {
        ensurePresence();
        return negotiatedResponseMediaType;
    }

    /**
     * Returns the negotiated producible media type.
     *
     * @deprecated Use {@link #negotiatedResponseMediaType()}.
     */
    @Deprecated
    @Nullable
    public MediaType negotiatedProduceType() {
        return negotiatedResponseMediaType();
    }

    /**
     * Sets the negotiated producible media type.
     */
    void setNegotiatedResponseMediaType(MediaType negotiatedResponseMediaType) {
        ensurePresence();
        this.negotiatedResponseMediaType = negotiatedResponseMediaType;
    }

    private void ensurePresence() {
        if (!isPresent()) {
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
