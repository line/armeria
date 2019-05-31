/*
 * Copyright 2015 LINE Corporation
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

import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * Matches the absolute path part of a URI and extracts path parameters from it.
 */
interface PathMapping {

    /**
     * Matches the specified {@code path} and extracts the path parameters from it.
     *
     * @param routingCtx a context to find the {@link Service}.
     *
     * @return a non-empty {@link RoutingResultBuilder} if the specified {@code path} matches this mapping.
     *         {@code null} otherwise.
     */
    @Nullable
    RoutingResultBuilder apply(RoutingContext routingCtx);

    /**
     * Returns the names of the path parameters extracted by this mapping.
     */
    Set<String> paramNames();

    /**
     * Returns the logger name.
     *
     * @return the logger name whose components are separated by a dot (.)
     */
    String loggerName();

    /**
     * Returns the value of the {@link Tag} in a {@link Meter} of this {@link PathMapping}.
     */
    String meterTag();

    /**
     * Returns the exact path of this {@link PathMapping} if this is an exact path mapping,
     * {@link Optional#empty()} otherwise.
     */
    Optional<String> exactPath();

    /**
     * Returns the prefix of this {@link PathMapping} if this is a {@link PrefixPathMapping} or a
     * {@link PathMappingWithPrefix}, {@link Optional#empty()} otherwise.
     *
     * @return the prefix that ends with '/' if this mapping is a prefix mapping
     */
    Optional<String> prefix();

    /**
     * Returns the trie path of this {@link PathMapping} if this has a trie matching condition,
     * {@link Optional#empty()} otherwise.
     */
    default Optional<String> triePath() {
        return Optional.empty();
    }

    /**
     * Returns the regular expression of this {@link PathMapping} if this is created with the regular
     * expression or glob pattern. Please note that this regex does not include the {@code pathPrefix} if
     * this is {@link PathMappingWithPrefix}. You should call {@link #prefix()} to retrieve that.
     */
    default Optional<String> regex() {
        return Optional.empty();
    }
}
