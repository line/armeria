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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;

@SuppressWarnings("rawtypes")
abstract class AbstractOAuth2AuthorizationGrantBuilder<T extends AbstractOAuth2AuthorizationGrantBuilder> {

    private final OAuth2AuthorizationGrantBuilder delegate;

    @Nullable
    private ClientAuthorization clientAuthorization;

    /**
     * A common abstraction for the requests implementing various Access Token request/response flows,
     * as per <a href="https://datatracker.ietf.org/doc/rfc6749/">[RFC6749]</a>.
     *
     * @param accessTokenEndpoint A {@link WebClient} to facilitate an Access Token request. Must correspond to
     *                            the Access Token endpoint of the OAuth 2 system.
     * @param accessTokenEndpointPath A URI path that corresponds to the Access Token endpoint of the
     *                                OAuth 2 system.
     */
    AbstractOAuth2AuthorizationGrantBuilder(WebClient accessTokenEndpoint, String accessTokenEndpointPath) {
        delegate = OAuth2AuthorizationGrant.builder(accessTokenEndpoint, accessTokenEndpointPath);
    }

    final OAuth2AuthorizationGrantBuilder delegate() {
        return delegate;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param clientAuthorization A supplier of encoded client authorization token.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     * @throws IllegalStateException if clientAuthorization already set
     */
    @SuppressWarnings("unchecked")
    public final T clientAuthorization(
            Supplier<String> clientAuthorization, String authorizationType) {
        if (this.clientAuthorization != null) {
            throw new IllegalStateException("either client authorization or client credentials already set");
        }
        this.clientAuthorization = ClientAuthorization.ofAuthorization(clientAuthorization, authorizationType);
        return (T) this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * {@code Basic} authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param clientAuthorization A supplier of encoded client authorization token.
     * @throws IllegalStateException if clientAuthorization already set
     */
    @SuppressWarnings("unchecked")
    public final T clientBasicAuthorization(Supplier<String> clientAuthorization) {
        if (this.clientAuthorization != null) {
            throw new IllegalStateException("either client authorization or client credentials already set");
        }
        this.clientAuthorization = ClientAuthorization.ofBasicAuthorization(clientAuthorization);
        return (T) this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on client credentials and
     * authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param clientCredentials A supplier of client credentials.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     * @throws IllegalStateException if clientCredentials already set
     */
    @SuppressWarnings("unchecked")
    public final T clientCredentials(
            Supplier<? extends Map.Entry<String, String>> clientCredentials, String authorizationType) {
        if (clientAuthorization != null) {
            throw new IllegalStateException("either client authorization or client credentials already set");
        }
        clientAuthorization = ClientAuthorization.ofCredentials(clientCredentials, authorizationType);
        return (T) this;
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on client credentials and
     * {@code Basic} authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param clientCredentials A supplier of client credentials.
     * @throws IllegalStateException if clientCredentials already set
     */
    @SuppressWarnings("unchecked")
    public final T clientCredentials(Supplier<? extends Map.Entry<String, String>> clientCredentials) {
        if (clientAuthorization != null) {
            throw new IllegalStateException("either client authorization or client credentials already set");
        }
        clientAuthorization = ClientAuthorization.ofCredentials(clientCredentials);
        return (T) this;
    }

    @Nullable
    final ClientAuthentication buildClientAuthentication() {
        if (clientAuthorization == null) {
            return null;
        }
        return clientAuthorization.toClientAuthentication();
    }

    /**
     * A period when the token should be refreshed proactively prior to its expiry.
     */
    @SuppressWarnings("unchecked")
    public final T refreshBefore(Duration refreshBefore) {
        delegate.refreshBefore(refreshBefore);
        return (T) this;
    }

    /**
     * An optional {@link Supplier} to acquire an access token before requesting it to the authorization server.
     * If the provided {@link GrantedOAuth2AccessToken} is valid, the client doesn't request a new token.
     *
     * <p>This is supposed to be used with {@link #newTokenConsumer(Consumer)} and gets executed
     * in the following cases:
     * <ul>
     *     <li>Before the first attempt to acquire an access token.</li>
     *     <li>Before a subsequent attempt after token issue or refresh failure.</li>
     * </ul>
     * @see #newTokenConsumer(Consumer)
     */
    @SuppressWarnings("unchecked")
    public final T fallbackTokenProvider(
            Supplier<CompletableFuture<? extends GrantedOAuth2AccessToken>> fallbackTokenProvider) {
        delegate.fallbackTokenProvider(fallbackTokenProvider);
        return (T) this;
    }

    /**
     * An optional hook which gets executed whenever a new token is issued.
     *
     * <p>This can be used in combination with {@link #fallbackTokenProvider(Supplier)} to store a newly issued
     * access token which will then be retrieved by invoking the fallback token provider.
     */
    @SuppressWarnings("unchecked")
    public final T newTokenConsumer(Consumer<? super GrantedOAuth2AccessToken> newTokenConsumer) {
        delegate.newTokenConsumer(requireNonNull(newTokenConsumer, "newTokenConsumer"));
        return (T) this;
    }
}
