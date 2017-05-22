/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.http.dynamic;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.PathParamExtractor;

/**
 * {@link HttpMethod}, {@link PathParamExtractor} and their corresponding {@link DynamicHttpFunction} instance.
 */
final class DynamicHttpFunctionEntry {

    /**
     * EnumSet of HTTP Method, e.g., GET, POST, PUT, ...
     */
    private final Set<HttpMethod> methods;

    /**
     * Path param extractor with placeholders, e.g., "/const1/{var1}/{var2}/const2"
     */
    private final PathParamExtractor pathParamExtractor;

    /**
     * {@link DynamicHttpFunction} instance that will be invoked with given {@link HttpMethod} and
     * {@link PathParamExtractor}.
     */
    private final DynamicHttpFunction function;

    /**
     * Creates a new instance.
     */
    DynamicHttpFunctionEntry(Set<HttpMethod> methods, PathParamExtractor pathParamExtractor,
                             DynamicHttpFunction function) {
        this.methods = Sets.immutableEnumSet(requireNonNull(methods, "methods"));
        this.pathParamExtractor = requireNonNull(pathParamExtractor, "pathParamExtractor");
        this.function = requireNonNull(function, "function");
    }

    /**
     * Returns whether it's mapping overlaps with given {@link DynamicHttpFunctionEntry} instance.
     */
    boolean overlaps(DynamicHttpFunctionEntry entry) {
        return !Sets.intersection(methods, entry.methods).isEmpty() &&
               pathParamExtractor.skeleton().equals(entry.pathParamExtractor.skeleton());
    }

    /**
     * Returns bound values and mapped function when given HTTP Method and {@link PathParamExtractor} matches.
     *
     * @see MappedDynamicFunction
     */
    @Nullable
    MappedDynamicFunction bind(HttpMethod method, String mappedPath) {
        if (!methods.contains(method)) {
            return null;
        }

        Map<String, String> args = pathParamExtractor.extract(mappedPath);
        if (args == null) {
            return null;
        }

        return new MappedDynamicFunction(function, args);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("methods", methods)
                          .add("pathParamExtractor", pathParamExtractor)
                          .add("function", function).toString();
    }
}
