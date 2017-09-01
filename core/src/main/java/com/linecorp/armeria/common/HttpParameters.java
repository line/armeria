/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import io.netty.handler.codec.Headers;

/**
 * HTTP parameters map.
 */
public interface HttpParameters extends Headers<String, String, HttpParameters> {

    /**
     * An immutable empty HTTP parameters map.
     */
    HttpParameters EMPTY_PARAMETERS = of().asImmutable();

    /**
     * Returns a new empty HTTP parameters map.
     */
    static HttpParameters of() {
        return new DefaultHttpParameters();
    }

    /**
     * Returns a new HTTP parameters map with the specified {@code Map<String, ? extends Iterable<String>>}.
     */
    static HttpParameters copyOf(Map<String, ? extends Iterable<String>> parameters) {
        requireNonNull(parameters, "parameters");
        final HttpParameters httpParameters = new DefaultHttpParameters();
        parameters.forEach((name, values) ->
                                   values.forEach(value -> httpParameters.add(name, value)));
        return httpParameters;
    }

    /**
     * Returns a copy of the specified {@code Headers<String, String, ?>}.
     */
    static HttpParameters copyOf(Headers<String, String, ?> parameters) {
        return of().set(requireNonNull(parameters, "parameters"));
    }

    /**
     * Returns the immutable view of this parameters map.
     */
    default HttpParameters asImmutable() {
        return new ImmutableHttpParameters(this);
    }
}
