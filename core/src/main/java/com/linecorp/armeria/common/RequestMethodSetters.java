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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Provide the setters for building an {@link HttpMethod} and a request path.
 */
@UnstableApi
public interface RequestMethodSetters {

    /**
     * Sets GET method and path.
     */
    RequestMethodSetters get(String path);

    /**
     * Sets POST method and path.
     */
    RequestMethodSetters post(String path);

    /**
     * Sets PUT method and path.
     */
    RequestMethodSetters put(String path);

    /**
     * Sets DELETE method and path.
     */
    RequestMethodSetters delete(String path);

    /**
     * Sets PATCH method and path.
     */
    RequestMethodSetters patch(String path);

    /**
     * Sets OPTIONS method and path.
     */
    RequestMethodSetters options(String path);

    /**
     * Sets HEAD method and path.
     */
    RequestMethodSetters head(String path);

    /**
     * Sets TRACE method and path.
     */
    RequestMethodSetters trace(String path);

    /**
     * Sets the method for this request.
     *
     * @see HttpMethod
     */
    RequestMethodSetters method(HttpMethod method);

    /**
     * Sets the path for this request.
     */
    RequestMethodSetters path(String path);
}
