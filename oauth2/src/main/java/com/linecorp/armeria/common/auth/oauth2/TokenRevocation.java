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

package com.linecorp.armeria.common.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ACCESS_TOKEN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.REFRESH_TOKEN;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.auth.oauth2.TokenRevocationRequest;

/**
 * Implements Token Revocation request/response flow,
 * as per <a href="https://datatracker.ietf.org/doc/rfc7009/">[RFC7009]</a>.
 */
@UnstableApi
public final class TokenRevocation {

    /**
     * Creates a new builder for {@link TokenRevocation}.
     */
    public static TokenRevocationBuilder builder(WebClient revocationEndpoint, String revocationEndpointPath) {
        return new TokenRevocationBuilder(revocationEndpoint, revocationEndpointPath);
    }

    private final TokenRevocationRequest request;

    TokenRevocation(WebClient revocationEndpoint, String revocationEndpointPath,
                    @Nullable ClientAuthorization clientAuthorization) {
        request = new TokenRevocationRequest(revocationEndpoint, revocationEndpointPath, clientAuthorization);
    }

    /**
     * Implements Token Revocation request/response flow,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc7009#section-2">[RFC7009], Section 2</a>.
     */
    public CompletableFuture<Boolean> revokeRefreshToken(String refreshToken) {
        return request.make(refreshToken, REFRESH_TOKEN);
    }

    /**
     * Implements Token Revocation request/response flow,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc7009#section-2">[RFC7009], Section 2</a>.
     */
    public CompletableFuture<Boolean> revokeAccessToken(String accessToken) {
        return request.make(accessToken, ACCESS_TOKEN);
    }
}
