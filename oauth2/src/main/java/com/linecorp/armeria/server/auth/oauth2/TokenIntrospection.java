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

package com.linecorp.armeria.server.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.ACCESS_TOKEN;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor;
import com.linecorp.armeria.common.auth.oauth2.TokenOperationRequest;
import com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Endpoint;

/**
 * Implements Token introspection request/response flow, as per
 * <a href="https://datatracker.ietf.org/doc/html/rfc7662">RFC 7662</a>.
 */
final class TokenIntrospection {

    private final OAuth2Endpoint<OAuth2TokenDescriptor> introspectionEndpoint;
    @Nullable
    private final Supplier<ClientAuthentication> clientAuthenticationSupplier;

    TokenIntrospection(OAuth2Endpoint<OAuth2TokenDescriptor> introspectionEndpoint,
                       @Nullable Supplier<ClientAuthentication> clientAuthenticationSupplier) {
        this.introspectionEndpoint = introspectionEndpoint;
        this.clientAuthenticationSupplier = clientAuthenticationSupplier;
    }

    CompletableFuture<OAuth2TokenDescriptor> introspect(String accessToken) {
        return introspect(accessToken, ACCESS_TOKEN);
    }

    CompletableFuture<OAuth2TokenDescriptor> introspect(String token, String tokenTypeHint) {
        final ClientAuthentication clientAuthentication = clientAuthentication();
        final TokenOperationRequest tokenOperationRequest = TokenOperationRequest.of(clientAuthentication,
                                                                                     token, tokenTypeHint);
        return introspectionEndpoint.execute(tokenOperationRequest);
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
