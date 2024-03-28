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

package com.linecorp.armeria.client.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2AccessToken;
import com.linecorp.armeria.internal.client.auth.oauth2.MockOAuth2JsonWebTokenService;
import com.linecorp.armeria.internal.server.auth.oauth2.MockOAuth2IntrospectionService;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class OAuth2JsonWebTokenGrantTest {

    static final String CLIENT_CREDENTIALS = "dGVzdF9jbGllbnQ6Y2xpZW50X3NlY3JldA=="; //test_client:client_secret
    static final String SERVER_CREDENTIALS = "dGVzdF9zZXJ2ZXI6c2VydmVyX3NlY3JldA=="; //test_server:server_secret
    static final long EXPIRES_IN_HOURS = 3L;

    static final MockOAuth2AccessToken TOKEN =
            MockOAuth2AccessToken.generate("test_client", null, Duration.ofHours(EXPIRES_IN_HOURS),
                                           ImmutableMap.of("extension_field", "twenty-seven"), "read", "write");

    static final HttpService SERVICE = new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    };

    @RegisterExtension
    static ServerExtension authServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/token", new MockOAuth2JsonWebTokenService()
                    .withJsonWebToken("test_client", "client_secret")
                    .withAuthorizedClient("test_client", "client_secret")
                    .withClientToken("test_client", TOKEN));
            sb.annotatedService("/introspect", new MockOAuth2IntrospectionService()
                    .withAuthorizedClient("test_client", "client_secret")
                    .withAuthorizedClient("test_server", "server_secret")
                    .withClientToken("test_client", TOKEN));
        }
    };

    @RegisterExtension
    final ServerExtension resourceServer = new ServerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final WebClient introspectClient = WebClient.of(authServer.httpUri());
            sb.service("/resource-read-write/", AuthService.builder().addOAuth2(
                    OAuth2TokenIntrospectionAuthorizer
                            .builder(introspectClient, "/introspect/token/")
                            .realm("protected resource read-write")
                            .accessTokenType("Bearer")
                            .clientAuthentication(
                                    ClientAuthentication.ofClientPassword("test_server", "server_secret"))
                            .permittedScope("read", "write")
                            .build()
            ).build(SERVICE));
            sb.service("/resource-read/",
                       AuthService.builder().addOAuth2(
                               OAuth2TokenIntrospectionAuthorizer
                                       .builder(introspectClient, "/introspect/token/")
                                       .realm("protected resource read")
                                       .accessTokenType("Bearer")
                                       .clientAuthentication(ClientAuthentication.ofBasic(SERVER_CREDENTIALS))
                                       .permittedScope("read")
                                       .build()
                       ).build(SERVICE));
            sb.service("/resource-read-write-update/",
                       AuthService.builder().addOAuth2(
                               OAuth2TokenIntrospectionAuthorizer
                                       .builder(introspectClient, "/introspect/token/")
                                       .realm("protected resource read-write-update")
                                       .accessTokenType("Bearer")
                                       .clientAuthentication(() -> {
                                           return ClientAuthentication.ofBasic(SERVER_CREDENTIALS);
                                       })
                                       .permittedScope("read", "write", "update")
                                       .build()
                       ).build(SERVICE));
        }
    };

    @Test
    void testOk() throws Exception {
        final String jwtToken = generateJwtToken("test_client", "client_secret");
        final ClientAuthentication clientAuthentication = ClientAuthentication.ofBasic(CLIENT_CREDENTIALS);
        final AccessTokenRequest accessTokenRequest =
                AccessTokenRequest.ofJsonWebToken(jwtToken, clientAuthentication, null);
        final OAuth2AuthorizationGrant grant =
                OAuth2AuthorizationGrant.builder(authServer.webClient(), "/token/jwt/")
                                        .accessTokenRequest(accessTokenRequest)
                                        .build();

        final WebClient client = WebClient.builder(resourceServer.httpUri())
                                          .decorator(OAuth2Client.newDecorator(grant))
                                          .build();

        final HttpRequest request1 = HttpRequest.of(HttpMethod.GET, "/resource-read-write/");
        final HttpRequest request2 = HttpRequest.of(HttpMethod.GET, "/resource-read/");
        final HttpRequest request3 = HttpRequest.of(HttpMethod.GET, "/resource-read-write-update/");

        final CompletableFuture<AggregatedHttpResponse> response1 = client.execute(request1).aggregate();
        final CompletableFuture<AggregatedHttpResponse> response2 = client.execute(request2).aggregate();
        final CompletableFuture<AggregatedHttpResponse> response3 = client.execute(request3).aggregate();

        assertThat(response1.get().status()).isEqualTo(HttpStatus.OK);
        assertThat(response2.get().status()).isEqualTo(HttpStatus.OK);
        assertThat(response3.get().status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static String generateJwtToken(String subject, String secret) throws Exception {
        final Instant currentTime = Instant.now();
        return JWT.create()
                  .withIssuer("armeria.dev")
                  .withSubject(subject)
                  .withIssuedAt(currentTime)
                  .withExpiresAt(currentTime.plusSeconds(3600))
                  .withAudience("armeria.dev")
                  .sign(Algorithm.HMAC384(secret));
    }
}
