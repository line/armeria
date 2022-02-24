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

package com.linecorp.armeria.internal.server;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.server.Route;

/**
 * A utility class for {@link Route}.
 */
public final class RouteUtil {

    /**
     * The prefix which represents an exact path.
     */
    public static final String EXACT = "exact:";

    /**
     * The prefix which represents a prefix path.
     */
    public static final String PREFIX = "prefix:";

    /**
     * The prefix which represents a glob path.
     */
    public static final String GLOB = "glob:";

    /**
     * The prefix which represents a regex path.
     */
    public static final String REGEX = "regex:";

    /**
     * Ensures that the specified {@code path} is an absolute path that starts with {@code "/"}.
     *
     * @return {@code path}
     *
     * @throws NullPointerException if {@code path} is {@code null}
     * @throws IllegalArgumentException if {@code path} is not an absolute path
     */
    public static String ensureAbsolutePath(String path, String paramName) {
        requireNonNull(path, paramName);
        if (path.isEmpty() || path.charAt(0) != '/') {
            throw new IllegalArgumentException(paramName + ": " + path +
                                               " (expected: an absolute path starting with '/')");
        }
        return path;
    }

    private RouteUtil() {}
}
