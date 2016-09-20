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

import java.util.Optional;

final class PathManipulators {

    abstract static class PathMappingWrapper extends AbstractPathMapping {
        private final PathMapping mapping;

        PathMappingWrapper(PathMapping mapping) {
            requireNonNull(mapping, "mapping");
            this.mapping = mapping;
        }

        PathMapping mapping() {
            return mapping;
        }

        @Override
        public String loggerName() {
            return mapping.loggerName();
        }

        @Override
        public String metricName() {
            return mapping.metricName();
        }

        @Override
        public Optional<String> exactPath() {
            return mapping.exactPath();
        }

    }

    static final class StripPrefixByNumPathComponents extends PathMappingWrapper {

        private final int numPathComponents;

        StripPrefixByNumPathComponents(PathMapping mapping, int numPathComponents) {
            super(mapping);
            if (numPathComponents <= 0) {
                throw new IllegalArgumentException(
                        "numPathComponents: " + numPathComponents + " (expected: > 0)");
            }

            this.numPathComponents = numPathComponents;
        }

        @Override
        protected String doApply(String path) {
            path = mapping().apply(path);
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
            return "stripPrefix(" + numPathComponents + ", " + mapping() + ')';
        }
    }

    static class StripPrefixByPathPrefix extends PathMappingWrapper {

        private final String pathPrefix;

        StripPrefixByPathPrefix(PathMapping mapping, String pathPrefix) {
            super(mapping);
            ensureAbsolutePath(pathPrefix, "pathPrefix");

            if (pathPrefix.endsWith("/")) {
                this.pathPrefix = pathPrefix;
            } else {
                this.pathPrefix = pathPrefix + '/';
            }
        }

        @Override
        protected String doApply(String path) {
            path = mapping().apply(path);
            if (path == null || !path.startsWith(pathPrefix)) {
                return null;
            }

            return path.substring(pathPrefix.length() - 1);
        }

        @Override
        public String toString() {
            return "stripPrefix(" + pathPrefix + ", " + mapping() + ')';
        }
    }

    static class StripParents extends PathMappingWrapper {

        StripParents(PathMapping mapping) {
            super(mapping);
        }

        @Override
        protected String doApply(String path) {
            path = mapping().apply(path);
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
            return "stripParents(" + mapping() + ')';
        }
    }

    static class Prepend extends PathMappingWrapper {

        private final String pathPrefix;

        Prepend(PathMapping mapping, String pathPrefix) {
            super(mapping);
            ensureAbsolutePath(pathPrefix, "pathPrefix");

            if (pathPrefix.endsWith("/")) {
                this.pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
            } else {
                this.pathPrefix = pathPrefix;
            }
        }

        @Override
        protected String doApply(String path) {
            path = mapping().apply(path);
            if (path == null) {
                return null;
            }

            return pathPrefix + path;
        }

        @Override
        public String toString() {
            return "prepend(" + pathPrefix + ", " + mapping() + ')';
        }
    }

    private PathManipulators() {}
}
