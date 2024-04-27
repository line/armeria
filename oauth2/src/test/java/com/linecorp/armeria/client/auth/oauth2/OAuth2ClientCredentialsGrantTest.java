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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableMap;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2AccessToken;
import com.linecorp.armeria.internal.client.auth.oauth2.MockOAuth2ClientCredentialsService;
import com.linecorp.armeria.internal.server.auth.oauth2.MockOAuth2IntrospectionService;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class OAuth2ClientCredentialsGrantTest {

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
            sb.annotatedService("/token", new MockOAuth2ClientCredentialsService()
                    .withAuthorizedClient("test_client", "client_secret")
                    .withAuthorizedClient("test_server", "server_secret")
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

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void testOk(boolean useLegacyApi) throws Exception {
        final WebClient authClient = WebClient.of(authServer.httpUri());
        final OAuth2AuthorizationGrant grant;
        if (useLegacyApi) {
            grant = OAuth2ClientCredentialsGrant
                    .builder(authClient, "/token/client/")
                    .clientBasicAuthorization(() -> CLIENT_CREDENTIALS).build();
        } else {
            final AccessTokenRequest accessTokenRequest =
                    AccessTokenRequest.ofClientCredentials("test_client", "client_secret");
            grant = OAuth2AuthorizationGrant
                    .builder(authClient, "/token/client/")
                    .accessTokenRequest(accessTokenRequest)
                    .build();
        }

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

    @Test
    void testConcurrent() throws Exception {
        final WebClient authClient = WebClient.of(authServer.httpUri());
        final ClientAuthentication clientAuthentication =
                ClientAuthentication.ofAuthorization("Basic", CLIENT_CREDENTIALS);
        final AccessTokenRequest accessTokenRequest =
                AccessTokenRequest.ofClientCredentials(clientAuthentication);
        final DefaultOAuth2AuthorizationGrant grant = (DefaultOAuth2AuthorizationGrant) spy(
                OAuth2AuthorizationGrant
                        .builder(authClient, "/token/client/")
                        .accessTokenRequest(() -> accessTokenRequest)
                        .build());

        final WebClient client = WebClient.builder(resourceServer.httpUri())
                                          .decorator(OAuth2Client.newDecorator(grant))
                                          .build();

        final int count = 10;
        final List<AggregatedHttpResponse> responses1 =
                getConcurrently(client, "/resource-read-write/", count).join();
        validateResponses(responses1, HttpStatus.OK);
        verify(grant, times(1)).obtainAccessToken(any());
        verify(grant, times(0)).refreshAccessToken(any(), any());

        final List<AggregatedHttpResponse> responses2 =
                getConcurrently(client, "/resource-read/", count).join();
        validateResponses(responses2, HttpStatus.OK);
        verify(grant, times(1)).obtainAccessToken(any());
        verify(grant, times(0)).refreshAccessToken(any(), any());

        final List<AggregatedHttpResponse> responses3 =
                getConcurrently(client, "/resource-read-write-update/", count).join();
        validateResponses(responses3, HttpStatus.FORBIDDEN);
        verify(grant, times(1)).obtainAccessToken(any());
        verify(grant, times(0)).refreshAccessToken(any(), any());
    }

    @Test
    void testConcurrent_refresh() throws Exception {
        final WebClient authClient = WebClient.of(authServer.httpUri());
        final AccessTokenRequest accessTokenRequest =
                AccessTokenRequest.ofClientCredentials("test_client", "client_secret");
        final DefaultOAuth2AuthorizationGrant grant = (DefaultOAuth2AuthorizationGrant) spy(
                OAuth2AuthorizationGrant
                        .builder(authClient, "/token/client/")
                        .accessTokenRequest(accessTokenRequest)
                        // Token is considered expired after 3 seconds
                        .refreshBefore(Duration.ofSeconds(EXPIRES_IN_HOURS * 3600 - 3))
                        .build());

        final WebClient client = WebClient.builder(resourceServer.httpUri())
                                          .decorator(OAuth2Client.newDecorator(grant))
                                          .build();
        final AggregatedHttpResponse response = client.get("/resource-read/").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        verify(grant, times(1)).obtainAccessToken(any());
        verify(grant, times(0)).refreshAccessToken(any(), any());

        Thread.sleep(3000L); // Wait until token expires.
        final List<AggregatedHttpResponse> responses =
                getConcurrently(client, "/resource-read/", 10).join();
        validateResponses(responses, HttpStatus.OK);
        verify(grant, times(1)).obtainAccessToken(any());
        verify(grant, times(1)).refreshAccessToken(any(), any());
    }

    private static CompletableFuture<List<AggregatedHttpResponse>> getConcurrently(
            WebClient client, String resource, int count) {
        final List<CompletableFuture<AggregatedHttpResponse>> futures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            futures.add(client.get(resource).aggregate());
        }
        return CompletableFutures.allAsList(futures);
    }

    private static void validateResponses(List<AggregatedHttpResponse> responses, HttpStatus status) {
        for (AggregatedHttpResponse response : responses) {
            assertThat(response.status()).isEqualTo(status);
        }
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void testUnauthorized(boolean useLegacyApi) {
        final WebClient authClient = WebClient.of(authServer.httpUri());
        final OAuth2AuthorizationGrant grant;
        if (useLegacyApi) {
            grant = OAuth2ClientCredentialsGrant
                    .builder(authClient, "/token/client/")
                    .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                    .build();
        } else {
            final ClientAuthentication clientAuthentication =
                    ClientAuthentication.ofAuthorization("Basic", SERVER_CREDENTIALS);
            final AccessTokenRequest accessTokenRequest =
                    AccessTokenRequest.ofClientCredentials(clientAuthentication);
            grant = OAuth2AuthorizationGrant
                    .builder(authClient, "/token/client/")
                    .accessTokenRequest(() -> accessTokenRequest)
                    .build();
        }
        final WebClient client = WebClient.builder(resourceServer.httpUri())
                                          .decorator(OAuth2Client.newDecorator(grant))
                                          .build();

        assertThatThrownBy(() -> client.get("/resource-read-write/").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(InvalidClientException.class);
    }
}
