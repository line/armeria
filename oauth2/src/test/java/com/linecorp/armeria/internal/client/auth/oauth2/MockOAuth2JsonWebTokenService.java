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

package com.linecorp.armeria.internal.client.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.JWT_GRANT_TYPE;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

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
public class MockOAuth2JsonWebTokenService extends MockOAuth2Service {

    private final Map<String, String> clientCredentials = new HashMap<>(); // clientId -> clientPassword

    public MockOAuth2JsonWebTokenService withJsonWebToken(String clientId, String clientPassword) {
        clientCredentials.put(clientId, clientPassword);
        return this;
    }

    @Post("/jwt/")
    @Consumes("application/x-www-form-urlencoded")
    public HttpResponse handleTokenGet(@Header("Authorization") Optional<String> auth,
                                       @Param("grant_type") Optional<String> grantType,
                                       @Param("assertion") Optional<String> assertion,
                                       @Param("scope") Optional<String> scope) {
        // Intentionally delay execution to test concurrent token update scenario.
        return HttpResponse.delayed(handleTokenGet0(auth, grantType, assertion, scope), Duration.ofMillis(100));
    }

    private HttpResponse handleTokenGet0(Optional<String> auth, Optional<String> grantType,
                                        Optional<String> assertion, Optional<String> scope) {
        // first, check "Authorization"
        final HttpResponse response = verifyClientCredentials(auth, "token grant");
        if (response != null) {
            return response; // UNAUTHORIZED or BAD_REQUEST
        }
        // check "grant_type"
        if (!grantType.isPresent() || !assertion.isPresent()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, INVALID_REQUEST);
        }

        if (!JWT_GRANT_TYPE.equals(grantType.get())) {
            // in case the authenticated client is not authorized to use this authorization grant type
            //return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, UNAUTHORIZED_CLIENT);

            // in case the authorization grant type is not supported by the authorization server
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, UNSUPPORTED_GRANT_TYPE);
        }

        // extract "subject" from "assertion"
        final DecodedJWT decoded = JWT.decode(assertion.get());
        final String clientId = decoded.getSubject();
        final String clientPassword = clientCredentials.get(clientId);
        try {
            JWT.require(Algorithm.HMAC384(clientPassword))
               .withIssuer("armeria.dev")
               .withAudience("armeria.dev")
               .build()
               .verify(decoded);
        } catch (JWTVerificationException ex) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, INVALID_GRANT);
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
