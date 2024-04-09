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

package com.linecorp.armeria.internal.common.auth.oauth2;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.auth.oauth2.OAuth2Request;
import com.linecorp.armeria.common.auth.oauth2.OAuth2ResponseHandler;

/**
 * A simple wrapper around a WebClient that makes it easy to execute {@link OAuth2Request}s.
 */
public final class OAuth2Endpoint<T> {

    private final WebClient endpoint;
    private final String endpointPath;
    private final OAuth2ResponseHandler<T> responseHandler;

    public OAuth2Endpoint(WebClient endpoint, String endpointPath,
                          OAuth2ResponseHandler<T> responseHandler) {
        this.endpoint = endpoint;
        this.endpointPath = endpointPath;
        this.responseHandler = responseHandler;
    }

    public CompletableFuture<T> execute(OAuth2Request oAuth2Request) {
        final HttpRequest request = oAuth2Request.asHttpRequest(endpointPath);
        final QueryParams requestParams = oAuth2Request.bodyParams();
        return endpoint.execute(request)
                       .aggregate()
                       .thenApply(response -> responseHandler.handle(response, requestParams));
    }
}
