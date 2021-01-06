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

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds {@link TokenRevocation}.
 */
@UnstableApi
public final class TokenRevocationBuilder {

    private final WebClient revocationEndpoint;
    private final String revocationEndpointPath;

    @Nullable
    private ClientAuthorization clientAuthorization;

    /**
     * A common abstraction for the requests implementing various Access Token request/response flows,
     * as per <a href="https://tools.ietf.org/html/rfc6749">[RFC6749]</a>.
     *
     * @param revocationEndpoint A {@link WebClient} to facilitate an Token Revocation request. Must correspond
     *                           to the Token Revocation endpoint of the OAuth 2 system.
     * @param revocationEndpointPath A URI path that corresponds to the Token Revocation endpoint of the
     *                               OAuth 2 system.
     */
    TokenRevocationBuilder(WebClient revocationEndpoint, String revocationEndpointPath) {
        this.revocationEndpoint = requireNonNull(revocationEndpoint, "revocationEndpoint");
        this.revocationEndpointPath = requireNonNull(revocationEndpointPath, "revocationEndpointPath");
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     */
    public TokenRevocationBuilder clientAuthorization(
            Supplier<String> authorizationSupplier, String authorizationType) {
        clientAuthorization = ClientAuthorization.ofAuthorization(authorizationSupplier, authorizationType);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * {@code Basic} authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     */
    public TokenRevocationBuilder clientBasicAuthorization(Supplier<String> authorizationSupplier) {
        clientAuthorization = ClientAuthorization.ofBasicAuthorization(authorizationSupplier);
        return this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on client credentials and
     * authorization type,
     * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     */
    public TokenRevocationBuilder clientCredentials(
            Supplier<? extends Map.Entry<String, String>> credentialsSupplier, String authorizationType) {
        clientAuthorization = ClientAuthorization.ofCredentials(credentialsSupplier, authorizationType);
        return this;
    }

    /**
     * Builds a new instance of {@link TokenRevocation} using configured parameters.
     */
    public TokenRevocation build() {
        return new TokenRevocation(revocationEndpoint, revocationEndpointPath, clientAuthorization);
    }
}
