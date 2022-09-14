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

package com.linecorp.armeria.server.grpc;

/**
 * User provided options for customizing {@link HttpJsonTranscodingService}.
 */
public final class HttpJsonTranscodingOptions {

    private final boolean camelCaseQueryParams;

    /**
     * Returns a new {@link HttpJsonTranscodingOptionsBuilder}.
     */
    public static HttpJsonTranscodingOptionsBuilder builder() {
        return new HttpJsonTranscodingOptionsBuilder();
    }

    /**
     * Creates a new {@link HttpJsonTranscodingOptions} from given parameter(s).
     */
    public static HttpJsonTranscodingOptions of(boolean camelCaseQueryParams) {
        return new HttpJsonTranscodingOptions(camelCaseQueryParams);
    }

    private HttpJsonTranscodingOptions(boolean camelCaseQueryParams) {
        this.camelCaseQueryParams = camelCaseQueryParams;
    }

    /**
     * Returns the set camelCaseQueryParams option value.
     */
    public boolean camelCaseQueryParams() {
        return this.camelCaseQueryParams;
    }
}
