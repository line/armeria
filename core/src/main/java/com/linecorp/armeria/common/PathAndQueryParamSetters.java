/*
 * Copyright 2022 LINE Corporation
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

import java.util.Map;
import java.util.Map.Entry;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Provides the setters for building a path and a query string.
 */
@UnstableApi
public interface PathAndQueryParamSetters {

    /**
     * Sets a path param for this message.
     */
    PathAndQueryParamSetters pathParam(String name, Object value);

    /**
     * Sets multiple path params for this message.
     */
    PathAndQueryParamSetters pathParams(Map<String, ?> pathParams);

    /**
     * Disables path parameters substitution. If path parameter is not disabled and a parameter is specified
     * using {@code {}} or {@code :}, value is not found, an {@link IllegalStateException} is thrown.
     */
    PathAndQueryParamSetters disablePathParams();

    /**
     * Adds a query param for this message.
     */
    PathAndQueryParamSetters queryParam(String name, Object value);

    /**
     * Adds multiple query params for this message.
     */
    PathAndQueryParamSetters queryParams(Iterable<? extends Entry<? extends String, String>> queryParams);
}
