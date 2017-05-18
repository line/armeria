/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.http.dynamic;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.PathMappingResult;

/**
 * {@link HttpMethod}, {@link PathMapping} and their corresponding {@link DynamicHttpFunction} instance.
 */
final class DynamicHttpFunctionEntry {

    /**
     * EnumSet of HTTP Method, e.g., GET, POST, PUT, ...
     */
    private final Set<HttpMethod> methods;

    /**
     * Path param extractor with placeholders, e.g., "/const1/{var1}/{var2}/const2"
     */
    private final PathMapping pathMapping;

    /**
     * {@link DynamicHttpFunction} instance that will be invoked with given {@link HttpMethod} and
     * {@link PathMapping}.
     */
    private final DynamicHttpFunction function;

    /**
     * Creates a new instance.
     */
    DynamicHttpFunctionEntry(Set<HttpMethod> methods, PathMapping pathMapping,
                             DynamicHttpFunction function) {
        this.methods = Sets.immutableEnumSet(requireNonNull(methods, "methods"));
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
        this.function = requireNonNull(function, "function");
    }

    /**
     * Returns whether it's mapping overlaps with given {@link DynamicHttpFunctionEntry} instance.
     */
    boolean overlaps(DynamicHttpFunctionEntry entry) {
        return false;
        // FIXME(trustin): Make the path overlap detection work again.
        //return !Sets.intersection(methods, entry.methods).isEmpty() &&
        //       pathMapping.skeleton().equals(entry.pathMapping.skeleton());
    }

    /**
     * Returns bound values and mapped function when given HTTP Method and {@link PathMapping} matches.
     *
     * @see MappedDynamicFunction
     */
    @Nullable
    MappedDynamicFunction bind(HttpMethod method, String mappedPath) {
        if (!methods.contains(method)) {
            return null;
        }

        final PathMappingResult result = pathMapping.apply(mappedPath, null);
        if (!result.isPresent()) {
            return null;
        }

        return new MappedDynamicFunction(function, result.pathParams());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("methods", methods)
                          .add("pathMapping", pathMapping)
                          .add("function", function).toString();
    }
}
