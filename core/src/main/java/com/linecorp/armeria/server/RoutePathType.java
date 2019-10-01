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

/**
 * The type of the path which was specified when a {@link Route} is created.
 *
 * @see RouteBuilder#path(String)
 */
public enum RoutePathType {

    /**
     * The exact path type. e.g, "/foo"
     */
    EXACT(true),

    /**
     * The prefix path type. e.g, "/", "/foo/"
     */
    PREFIX(true),

    /**
     * The path which contains path parameters. e.g, "/:", "/foo/:/bar/:"
     */
    PARAMETERIZED(true),

    /**
     * The regex path type. e.g, {@code "^/(?<foo>.*)$"}
     * The {@link Route} which is created using {@link RouteBuilder#glob(String)} and
     * {@link RouteBuilder#regex(String)} can be this type.
     */
    REGEX(false),

    /**
     * The path which has the prefix and the regex.
     *
     * @see RouteBuilder#path(String, String)
     */
    REGEX_WITH_PREFIX(false);

    private final boolean hasTriePath;

    RoutePathType(boolean hasTriePath) {
        this.hasTriePath = hasTriePath;
    }

    /**
     * Tells whether this {@link RoutePathType} has a trie path or not.
     */
    public boolean hasTriePath() {
        return hasTriePath;
    }
}
