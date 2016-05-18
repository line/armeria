/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

/**
 * A skeletal {@link PathMapping} implementation. Implement {@link #doApply(String)}.
 */
public abstract class AbstractPathMapping implements PathMapping {

    /**
     * Matches the specified {@code path} and translates the matched {@code path} to another path string.
     * This method performs sanity checks on the specified {@code path}, calls {@link #doApply(String)},
     * and then performs sanity checks on the returned {@code mappedPath}.
     *
     * @param path an absolute path as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>
     * @return the translated path which is used as the value of {@link ServiceRequestContext#mappedPath()}.
     *         {@code null} if the specified {@code path} does not match this mapping.
     */
    @Override
    public final String apply(String path) {
        requireNonNull(path, "path");
        if (path.isEmpty() || path.charAt(0) != '/') {
            throw new IllegalArgumentException("path: " + path + " (expected: an absolute path)");
        }

        final String mappedPath = doApply(path);
        if (mappedPath == null) {
            return null;
        }

        if (mappedPath.isEmpty()) {
            throw new IllegalStateException("mapped path is not an absolute path: <empty>");
        }

        if (mappedPath.charAt(0) != '/') {
            throw new IllegalStateException("mapped path is not an absolute path: " + mappedPath);
        }

        return mappedPath;
    }

    /**
     * Invoked by {@link #apply(String)} to perform the actual path matching and translation.
     *
     * @param path an absolute path as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>
     * @return the translated path which is used as the value of {@link ServiceRequestContext#mappedPath()}.
     *         {@code null} if the specified {@code path} does not match this mapping.
     */
    protected abstract String doApply(String path);
}
