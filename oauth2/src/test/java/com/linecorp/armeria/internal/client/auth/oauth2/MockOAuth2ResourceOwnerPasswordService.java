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

package com.linecorp.armeria.internal.client.auth.oauth2;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2AccessToken;
import com.linecorp.armeria.internal.common.auth.oauth2.MockOAuth2Service;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

@LoggingDecorator
public class MockOAuth2ResourceOwnerPasswordService extends MockOAuth2Service {

    private final Map<String, String> authorizedUsers = new HashMap<>(); // username -> password
    private final Map<String, String> clientUsers = new HashMap<>(); // clientId -> username

    public MockOAuth2ResourceOwnerPasswordService withAuthorizedUser(String clientId,
        String username, String password) {
      authorizedUsers.put(username, password);
      clientUsers.put(clientId, username);
      return this;
    }

    private boolean isAuthorizedUser(String clientId, String username, String password) {
        return password.equals(authorizedUsers.get(username)) && username.equals(clientUsers.get(clientId));
    }

    @Post("/user/")
    @Consumes("application/x-www-form-urlencoded")
    public HttpResponse handleTokenGet(
            @Header("Authorization") Optional<String> auth,
            @Param("grant_type") Optional<String> grantType,
            @Param("username") Optional<String> username,
            @Param("password") Optional<String> password,
            @Param("scope") Optional<String> scope) {

        // first, check "Authorization"
        final HttpResponse response = verifyClientCredentials(auth, "token grant");
        if (response != null) {
            return response; // UNAUTHORIZED or BAD_REQUEST
        }
        // extract "client_id" from "Authorization"
        final String clientId = extractClientId(auth.orElse(null));

        // check "grant_type"
        if (!grantType.isPresent()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, INVALID_REQUEST);
        }
        if (!"password".equals(grantType.get())) {
            // in case the authenticated client is not authorized to use this authorization grant type
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, UNAUTHORIZED_CLIENT);

            // in case the authorization grant type is not supported by the authorization server
            //return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, UNSUPPORTED_GRANT_TYPE);
        }

        // check user credentials
        if (!username.isPresent() || !password.isPresent()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, INVALID_REQUEST);
        }
        if (!isAuthorizedUser(clientId, username.get(), password.get())) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, INVALID_CLIENT);
        }

        // check for client access tokens
        final Set<String> clientTokens = findClientTokens(clientId);

        if (clientTokens.isEmpty()) {
            // there are no tokens set for this client - client cannot be authorized
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, INVALID_CLIENT);
        }

        final String tokenId = clientTokens.iterator().next();
        final MockOAuth2AccessToken accessToken = requireNonNull(accessTokens().get(tokenId), tokenId);
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, accessToken.grantedToken().rawResponse());
    }
}
