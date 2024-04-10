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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.REFRESH_TOKEN;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Endpoint;

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

    private final OAuth2Endpoint<Boolean> revocationEndpoint;
    @Nullable
    private final Supplier<ClientAuthentication> clientAuthenticationSupplier;

    TokenRevocation(OAuth2Endpoint<Boolean> revocationEndpoint,
                    @Nullable Supplier<ClientAuthentication> clientAuthenticationSupplier) {
        this.revocationEndpoint = revocationEndpoint;
        this.clientAuthenticationSupplier = clientAuthenticationSupplier;
    }

    /**
     * Implements Token Revocation request/response flow,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc7009#section-2">[RFC7009], Section 2</a>.
     */
    public CompletableFuture<Boolean> revokeRefreshToken(String refreshToken) {
        final ClientAuthentication clientAuthentication = clientAuthentication();
        final TokenOperationRequest tokenOperationRequest =
                TokenOperationRequest.of(clientAuthentication, refreshToken, REFRESH_TOKEN);
        return revocationEndpoint.execute(tokenOperationRequest);
    }

    /**
     * Implements Token Revocation request/response flow,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc7009#section-2">[RFC7009], Section 2</a>.
     */
    public CompletableFuture<Boolean> revokeAccessToken(String accessToken) {
        final ClientAuthentication clientAuthentication = clientAuthentication();
        final TokenOperationRequest tokenOperationRequest =
                TokenOperationRequest.of(clientAuthentication, accessToken, REFRESH_TOKEN);
        return revocationEndpoint.execute(tokenOperationRequest);
    }

    @Nullable
    private ClientAuthentication clientAuthentication() {
        if (clientAuthenticationSupplier != null) {
            final ClientAuthentication clientAuthentication = clientAuthenticationSupplier.get();
            return requireNonNull(clientAuthentication, "clientAuthenticationSupplier.get() returned null");
        }
        return null;
    }
}
