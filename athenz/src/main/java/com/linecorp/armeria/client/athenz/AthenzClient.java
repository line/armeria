/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * An {@link HttpClient} that adds an Athenz token to the request headers.
 * {@link TokenType#ACCESS_TOKEN} and {@link TokenType#YAHOO_ROLE_TOKEN} are supported.
 *
 * <p>The acquired token is cached and automatically refreshed before it expires based on the specified
 * duration. If not specified, the default refresh duration is 10 minutes before the token expires.
 *
 * <p>Example:
 * <pre>{@code
 * import com.linecorp.armeria.client.athenz.ZtsBaseClient;
 * import com.linecorp.armeria.client.athenz.AthenzClient;
 *
 * ZtsBaseClient ztsBaseClient =
 *   ZtsBaseClient
 *     .builder("https://athenz.example.com:8443/zts/v1")
 *     .keyPair("/var/lib/athenz/service.key.pem", "/var/lib/athenz/service.cert.pem")
 *     .build();
 *
 * WebClient
 *   .builder()
 *   .decorator(AthenzClient.newDecorator(ztsBaseClient, "my-domain",
 *                                        TokenType.ROLE_TOKEN)
 *   ...
 *   .build();
 * }</pre>
 */
@UnstableApi
public final class AthenzClient extends SimpleDecoratingHttpClient {

    private static final Duration DEFAULT_REFRESH_BEFORE = Duration.ofMinutes(10);

    /**
     * Returns a new {@link HttpClient} decorator that obtains an Athenz token for the specified domain and
     * adds it to the request headers.
     *
     * @param ztsBaseClient the ZTS base client to use to communicate with the ZTS server
     * @param domainName the Athenz domain name
     * @param tokenType the type of Athenz token to obtain
     */
    public static Function<HttpClient, AthenzClient> newDecorator(ZtsBaseClient ztsBaseClient,
                                                                  String domainName, TokenType tokenType) {
        return newDecorator(ztsBaseClient, domainName, ImmutableList.of(), tokenType);
    }

    /**
     * Returns a new {@link HttpClient} decorator that obtains an Athenz token for the specified domain and
     * role name, and adds it to the request headers.
     *
     * @param ztsBaseClient the ZTS base client to use to communicate with the ZTS server
     * @param domainName the Athenz domain name
     * @param roleName the Athenz role name
     * @param tokenType the type of Athenz token to obtain
     */
    public static Function<HttpClient, AthenzClient> newDecorator(ZtsBaseClient ztsBaseClient,
                                                                  String domainName, String roleName,
                                                                  TokenType tokenType) {
        return newDecorator(ztsBaseClient, domainName, ImmutableList.of(roleName), tokenType);
    }

    /**
     * Returns a new {@link HttpClient} decorator that obtains an Athenz token for the specified domain and
     * role names, and adds it to the request headers.
     *
     * @param ztsBaseClient the ZTS base client to use to communicate with the ZTS server
     * @param domainName the Athenz domain name
     * @param roleNames the list of Athenz role names
     * @param tokenType the type of Athenz token to obtain
     */
    public static Function<HttpClient, AthenzClient> newDecorator(ZtsBaseClient ztsBaseClient,
                                                                  String domainName, List<String> roleNames,
                                                                  TokenType tokenType) {
        return newDecorator(ztsBaseClient, domainName, roleNames, tokenType, DEFAULT_REFRESH_BEFORE);
    }

    /**
     * Returns a new {@link HttpClient} decorator that obtains an Athenz token for the specified domain and
     * role names, and adds it to the request headers.
     *
     * @param ztsBaseClient the ZTS base client to use to communicate with the ZTS server
     * @param domainName the Athenz domain name
     * @param roleNames the list of Athenz role names
     * @param tokenType the type of Athenz token to obtain
     * @param refreshBefore the duration before the token expires to refresh it
     */
    public static Function<HttpClient, AthenzClient> newDecorator(ZtsBaseClient ztsBaseClient,
                                                                  String domainName, List<String> roleNames,
                                                                  TokenType tokenType, Duration refreshBefore) {
        requireNonNull(ztsBaseClient, "ztsBaseClient");
        requireNonNull(domainName, "domainName");
        requireNonNull(roleNames, "roleNames");
        final ImmutableList<String> roleNames0 = ImmutableList.copyOf(roleNames);
        requireNonNull(tokenType, "tokenType");
        requireNonNull(refreshBefore, "refreshBefore");
        return delegate -> new AthenzClient(delegate, ztsBaseClient, domainName, roleNames0,
                                            tokenType, refreshBefore);
    }

    private final TokenType tokenType;
    private final TokenClient tokenClient;

    private AthenzClient(HttpClient delegate, ZtsBaseClient ztsBaseClient, String domainName,
                         List<String> roleNames, TokenType tokenType, Duration refreshBefore) {
        super(delegate);
        this.tokenType = tokenType;
        if (tokenType.isRoleToken()) {
            tokenClient = new RoleTokenClient(ztsBaseClient, domainName, roleNames, refreshBefore);
        } else {
            tokenClient = new AccessTokenClient(ztsBaseClient, domainName, roleNames, refreshBefore);
        }
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> future = tokenClient.getToken().thenApply(token -> {
            final HttpRequest newReq = req.mapHeaders(headers -> {
                final RequestHeadersBuilder builder = headers.toBuilder();
                String token0 = token;
                if (tokenType.authScheme() != null) {
                    token0 = tokenType.authScheme() + ' ' + token0;
                }
                builder.set(tokenType.headerName(), token0);
                return builder.build();
            });
            ctx.updateRequest(newReq);
            try {
                return unwrap().execute(ctx, newReq);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        });

        return HttpResponse.of(future);
    }
}
