/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client.athenz;

import static com.linecorp.armeria.client.athenz.RoleTokenClient.ROLE_JOINER;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.collect.ImmutableList;
import com.yahoo.athenz.auth.AuthorityConsts;

import com.linecorp.armeria.client.auth.oauth2.AccessTokenRequest;
import com.linecorp.armeria.client.auth.oauth2.OAuth2AuthorizationGrant;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.athenz.AccessDeniedException;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.common.util.Exceptions;

public final class AccessTokenClient implements TokenClient {

    private final AtomicBoolean tlsKeyPairUpdated = new AtomicBoolean();

    private final long refreshBeforeMillis;
    private final String domainName;
    private final List<String> roleNames;
    private final OAuth2AuthorizationGrant authorizationGrant;

    AccessTokenClient(ZtsBaseClient ztsBaseClient, String domainName, List<String> roleNames,
                      Duration refreshBefore) {
        refreshBeforeMillis = refreshBefore.toMillis();
        this.domainName = domainName;
        this.roleNames = roleNames;

        ztsBaseClient.addTlsKeyPairListener(tlsKeyPair -> tlsKeyPairUpdated.set(true));

        // Scope syntax:
        // - <domain-name>:domain
        // - <domain-name>:role.<role-name>
        // https://github.com/AthenZ/athenz/blob/5e064414224eca025c7a4ae1df5b5eb381e71a16/clients/java/zts/src/main/java/com/yahoo/athenz/zts/ZTSClient.java#L1446
        final ImmutableList.Builder<String> scopeBuilder = ImmutableList.builder();
        if (roleNames.isEmpty()) {
            scopeBuilder.add(domainName + ":domain");
        } else {
            for (String role : roleNames) {
                scopeBuilder.add(domainName + AuthorityConsts.ROLE_SEP + role);
            }
        }
        final AccessTokenRequest tokenRequest = AccessTokenRequest.ofClientCredentials(
                NoopClientAuthentication.INSTANCE, scopeBuilder.build());
        authorizationGrant = OAuth2AuthorizationGrant.builder(ztsBaseClient.webClient(),
                                                              "/oauth2/token")
                                                     .accessTokenRequest(tokenRequest)
                                                     .refreshIf(this::shouldRefreshToken)
                                                     .build();
    }

    private boolean shouldRefreshToken(GrantedOAuth2AccessToken token) {
        if (tlsKeyPairUpdated.compareAndSet(true, false)) {
            // If the TLS key pair is updated, we need to refresh the token.
            return true;
        }
        final Duration expiresIn = token.expiresIn();
        if (expiresIn == null) {
            return false;
        }

        return !token.isValid(Instant.now().plusMillis(refreshBeforeMillis));
    }

    @Override
    public CompletableFuture<String> getToken() {
        return authorizationGrant.getAccessToken().handle((token, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                throw new AccessDeniedException("Failed to obtain an Athenz access token. (domain: " +
                                                domainName + ", roles: " + ROLE_JOINER.join(roleNames) + ')',
                                                cause);
            }
            return token.accessToken();
        }).toCompletableFuture();
    }

    private enum NoopClientAuthentication implements ClientAuthentication {

        INSTANCE;

        @Override
        public void addAsHeaders(HttpHeadersBuilder headersBuilder) {}

        @Override
        public void addAsBodyParams(QueryParamsBuilder formBuilder) {}
    }
}
