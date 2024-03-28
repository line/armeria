/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.auth.oauth2;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An interface that represents an OAuth 2.0 request.
 */
@UnstableApi
public interface OAuth2Request {

    /**
     * Returns the client authentication for this request.
     */
    @Nullable
    ClientAuthentication clientAuthentication();

    /**
     * Adds the body parameters of this request to the specified {@link QueryParamsBuilder}.
     */
    void addBodyParams(QueryParamsBuilder formBuilder);

    /**
     * Returns the body parameters of this request.
     */
    default QueryParams bodyParams() {
        final QueryParamsBuilder formBuilder = QueryParams.builder();
        addBodyParams(formBuilder);
        return formBuilder.build();
    }

    /**
     * Converts this OAuth 2.0 request into an {@link HttpRequest} with the specified {@code endpointPath}.
     */
    HttpRequest asHttpRequest(String endpointPath);
}
