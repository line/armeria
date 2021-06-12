/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client.auth.oauth2;

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;

/**
 * Represents an OAuth 2.0 Access Token Grant flow to obtain Access Token.
 */
@UnstableApi
@FunctionalInterface
public interface OAuth2AuthorizationGrant {

    /**
     * Produces OAuth 2.0 Access Token
     */
    CompletionStage<GrantedOAuth2AccessToken> getAccessToken();

    /**
     * Produces (if necessary) OAuth 2.0 Access Token and adds it to the {@code req} in form of the
     * {@code Authorization} header.
     * @param req {@link HttpRequest} to wrap with OAuth 2.0 authorization.
     * @return {@link CompletionStage} that refers to {@link HttpRequest} wrapped wrap with
     *         OAuth 2.0 authorization information.
     */
    default CompletionStage<HttpRequest> withAuthorization(HttpRequest req) {
        return getAccessToken().thenApply(accessToken -> {
            // Create a new request with an additional 'Authorization' header
            return req.withHeaders(req.headers().toBuilder()
                                      .set(HttpHeaderNames.AUTHORIZATION, accessToken.authorization())
                                      .build());
        });
    }
}
