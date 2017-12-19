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

import static java.util.Objects.requireNonNull;

import java.util.Optional;

/**
 * A skeletal {@link PathMapping} implementation. Implement {@link #doApply(PathMappingContext)}.
 */
public abstract class AbstractPathMapping implements PathMapping {

    /**
     * {@inheritDoc} This method performs sanity checks on the specified {@code path} and calls
     * {@link #doApply(PathMappingContext)}.
     */
    @Override
    public final PathMappingResult apply(PathMappingContext mappingCtx) {
        ensureAbsolutePath(mappingCtx.path(), "path");
        return doApply(mappingCtx);
    }

    /**
     * Ensures that the specified {@code path} is an absolute path.
     *
     * @return {@code path}
     *
     * @throws NullPointerException if {@code path} is {@code null}
     * @throws IllegalArgumentException if {@code path} is not an absolute path
     */
    protected static String ensureAbsolutePath(String path, String paramName) {
        requireNonNull(path, paramName);
        if (path.isEmpty() || path.charAt(0) != '/') {
            throw new IllegalArgumentException(paramName + ": " + path + " (expected: an absolute path)");
        }
        return path;
    }

    /**
     * Invoked by {@link #apply(PathMappingContext)} to perform the actual path matching and path parameter
     * extraction.
     *
     * @param mappingCtx a context to find the {@link Service}
     *
     * @return a non-empty {@link PathMappingResult} if the specified {@code path} matches this mapping.
     *         {@link PathMappingResult#empty()} if not matches.
     */
    protected abstract PathMappingResult doApply(PathMappingContext mappingCtx);

    @Override
    public String loggerName() {
        return "__UNKNOWN__";
    }

    static String loggerName(String pathish) {
        if (pathish == null) {
            return "__UNKNOWN__";
        }

        String normalized = pathish;
        if ("/".equals(normalized)) {
            return "__ROOT__";
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

    @Override
    public String meterTag() {
        return "__UNKNOWN_PATH__";
    }

    @Override
    public Optional<String> exactPath() {
        return Optional.empty();
    }

    @Override
    public Optional<String> prefix() {
        return Optional.empty();
    }
}
