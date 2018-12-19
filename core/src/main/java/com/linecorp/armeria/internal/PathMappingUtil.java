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

package com.linecorp.armeria.internal;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.server.PathMapping;

/**
 * A utility class for {@link PathMapping}.
 */
public final class PathMappingUtil {

    /**
     * The prefix which represents an exact path mapping.
     */
    public static final String EXACT = "exact:";

    /**
     * The prefix which represents a prefix path mapping.
     */
    public static final String PREFIX = "prefix:";

    /**
     * The prefix which represents a glob path mapping.
     */
    public static final String GLOB = "glob:";

    /**
     * The prefix which represents a regex path mapping.
     */
    public static final String REGEX = "regex:";

    public static final String UNKNOWN_LOGGER_NAME = "__UNKNOWN__";

    public static final String ROOT_LOGGER_NAME = "__ROOT__";

    /**
     * Ensures that the specified {@code path} is an absolute pathMapping.
     *
     * @return {@code path}
     *
     * @throws NullPointerException if {@code path} is {@code null}
     * @throws IllegalArgumentException if {@code path} is not an absolute pathMapping
     */
    public static String ensureAbsolutePath(String path, String paramName) {
        requireNonNull(path, paramName);
        if (path.isEmpty() || path.charAt(0) != '/') {
            throw new IllegalArgumentException(paramName + ": " + path + " (expected: an absolute path)");
        }
        return path;
    }

    /**
     * Returns the logger name from the specified {@code pathish}.
     */
    public static String newLoggerName(@Nullable String pathish) {
        if (pathish == null) {
            return UNKNOWN_LOGGER_NAME;
        }

        String normalized = pathish;
        if ("/".equals(normalized)) {
            return ROOT_LOGGER_NAME;
        }

        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1); // Strip the first slash.
        }

        final int end;
        if (normalized.endsWith("/")) {
            end = normalized.length() - 1;
        } else {
            end = normalized.length();
        }

        final StringBuilder buf = new StringBuilder(end);
        boolean start = true;
        for (int i = 0; i < end; i++) {
            final char ch = normalized.charAt(i);
            if (ch != '/') {
                if (start) {
                    start = false;
                    if (Character.isJavaIdentifierStart(ch)) {
                        buf.append(ch);
                    } else {
                        buf.append('_');
                        if (Character.isJavaIdentifierPart(ch)) {
                            buf.append(ch);
                        }
                    }
                } else {
                    if (Character.isJavaIdentifierPart(ch)) {
                        buf.append(ch);
                    } else {
                        buf.append('_');
                    }
                }
            } else {
                start = true;
                buf.append('.');
            }
        }

        return buf.toString();
    }

    private PathMappingUtil() {}
}
