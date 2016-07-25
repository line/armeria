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

final class PathManipulators {

    static final class StripPrefixByNumPathComponents extends AbstractPathMapping {

        private final PathMapping mapping;
        private final int numPathComponents;

        StripPrefixByNumPathComponents(PathMapping mapping, int numPathComponents) {
            requireNonNull(mapping, "mapping");
            if (numPathComponents <= 0) {
                throw new IllegalArgumentException(
                        "numPathComponents: " + numPathComponents + " (expected: > 0)");
            }

            this.mapping = mapping;
            this.numPathComponents = numPathComponents;
        }

        @Override
        protected String doApply(String path) {
            path = mapping.apply(path);
            if (path == null) {
                return null;
            }

            final int numPathComponents = this.numPathComponents;
            int i = 1; // Skip the first slash.
            int strips = 0;
            for (;;) {
                int nextI = path.indexOf('/', i);
                if (nextI < 0) {
                    return null;
                }
                strips++;
                if (strips == numPathComponents) {
                    return path.substring(nextI);
                }
            }
        }

        @Override
        public String toString() {
            return "stripPrefix(" + numPathComponents + ", " + mapping + ')';
        }
    }

    static class StripPrefixByPathPrefix extends AbstractPathMapping {

        private final PathMapping mapping;
        private final String pathPrefix;

        StripPrefixByPathPrefix(PathMapping mapping, String pathPrefix) {
            requireNonNull(mapping, "mapping");
            requireNonNull(pathPrefix, "pathPrefix");
            if (!pathPrefix.startsWith("/")) {
                throw new IllegalArgumentException("pathPrefix: " + pathPrefix + " (expected: an absolute path)");
            }

            this.mapping = mapping;
            if (pathPrefix.endsWith("/")) {
                this.pathPrefix = pathPrefix;
            } else {
                this.pathPrefix = pathPrefix + '/';
            }
        }

        @Override
        protected String doApply(String path) {
            path = mapping.apply(path);
            if (path == null || !path.startsWith(pathPrefix)) {
                return null;
            }

            return path.substring(pathPrefix.length() - 1);
        }

        @Override
        public String toString() {
            return "stripPrefix(" + pathPrefix + ", " + mapping + ')';
        }
    }

    static class StripParents extends AbstractPathMapping {

        private final PathMapping mapping;

        StripParents(PathMapping mapping) {
            this.mapping = requireNonNull(mapping, "mapping");
        }

        @Override
        protected String doApply(String path) {
            path = mapping.apply(path);
            if (path == null) {
                return null;
            }

            if (path.isEmpty()) {
                throw new IllegalStateException("mapped path is not an absolute path: <empty>");
            }

            // Start from (length - 1) to skip the last slash.
            final int lastSlashPos = path.lastIndexOf('/', path.length() - 1);
            return lastSlashPos >= 0 ? path.substring(lastSlashPos) : path;
        }

        @Override
        public String toString() {
            return "stripParents(" + mapping + ')';
        }
    }

    static class Prepend extends AbstractPathMapping {

        private final PathMapping mapping;
        private final String pathPrefix;

        Prepend(PathMapping mapping, String pathPrefix) {
            requireNonNull(mapping, "mapping");
            requireNonNull(pathPrefix, "pathPrefix");
            if (!pathPrefix.startsWith("/")) {
                throw new IllegalArgumentException("pathPrefix: " + pathPrefix + " (expected: an absolute path)");
            }

            this.mapping = mapping;
            if (pathPrefix.endsWith("/")) {
                this.pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
            } else {
                this.pathPrefix = pathPrefix;
            }
        }

        @Override
        protected String doApply(String path) {
            path = mapping.apply(path);
            if (path == null) {
                return null;
            }

            return pathPrefix + path;
        }

        @Override
        public String toString() {
            return "prepend(" + pathPrefix + ", " + mapping + ')';
        }
    }

    private PathManipulators() {}
}
