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

import java.time.Duration;
import java.util.AbstractMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2AccessToken;
import com.linecorp.armeria.internal.client.auth.oauth2.MockOAuth2ResourceOwnerPasswordService;
import com.linecorp.armeria.internal.server.auth.oauth2.MockOAuth2IntrospectionService;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class OAuth2ResourceOwnerPasswordCredentialsGrantTest {

    static final String CLIENT_CREDENTIALS = "dGVzdF9jbGllbnQ6Y2xpZW50X3NlY3JldA=="; //test_client:client_secret
    static final String SERVER_CREDENTIALS = "dGVzdF9zZXJ2ZXI6c2VydmVyX3NlY3JldA=="; //test_server:server_secret

    static final MockOAuth2AccessToken TOKEN =
            MockOAuth2AccessToken.generate("test_client", null, Duration.ofHours(3L),
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
            sb.annotatedService("/token", new MockOAuth2ResourceOwnerPasswordService()
                    .withAuthorizedUser("test_client", "test_user", "test_password")
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
            sb.service("/resource-read-write/",
                       AuthService.builder().addOAuth2(
                               OAuth2TokenIntrospectionAuthorizer
                                       .builder(introspectClient, "/introspect/token/")
                                       .realm("protected resource read-write")
                                       .accessTokenType("Bearer")
                                       .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                                       .permittedScope("read", "write")
                                       .build()
                       ).build(SERVICE));
            sb.service("/resource-read/",
                       AuthService.builder().addOAuth2(
                               OAuth2TokenIntrospectionAuthorizer
                                       .builder(introspectClient, "/introspect/token/")
                                       .realm("protected resource read")
                                       .accessTokenType("Bearer")
                                       .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                                       .permittedScope("read")
                                       .build()
                       ).build(SERVICE));
            sb.service("/resource-read-write-update/",
                       AuthService.builder().addOAuth2(
                               OAuth2TokenIntrospectionAuthorizer
                                       .builder(introspectClient, "/introspect/token/")
                                       .realm("protected resource read-write-update")
                                       .accessTokenType("Bearer")
                                       .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                                       .permittedScope("read", "write", "update")
                                       .build()
                       ).build(SERVICE));
        }
    };

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    public void testOk(boolean useLegacyApi) throws Exception {
        final WebClient authClient = WebClient.of(authServer.httpUri());

        final OAuth2AuthorizationGrant grant;
        if (useLegacyApi) {
            grant = OAuth2ResourceOwnerPasswordCredentialsGrant
                    .builder(authClient, "/token/user/")
                    .userCredentials(
                            () -> new AbstractMap.SimpleImmutableEntry<>("test_user", "test_password"))
                    .clientBasicAuthorization(() -> CLIENT_CREDENTIALS).build();
        } else {
            final ClientAuthentication clientAuthentication = ClientAuthentication.ofBasic(CLIENT_CREDENTIALS);
            final AccessTokenRequest accessTokenRequest =
                    AccessTokenRequest.ofResourceOwnerPassword("test_user", "test_password",
                                                               clientAuthentication, null);
            grant = OAuth2AuthorizationGrant.builder(authClient, "/token/user/")
                                            .accessTokenRequest(() -> accessTokenRequest)
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

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    public void testUnauthorized(boolean useLegacyApi) {
        final WebClient authClient = WebClient.of(authServer.httpUri());

        final OAuth2AuthorizationGrant grant;

        if (useLegacyApi) {
            grant = OAuth2ResourceOwnerPasswordCredentialsGrant
                    .builder(authClient, "/token/user/")
                    .userCredentials(
                            () -> new AbstractMap.SimpleImmutableEntry<>("test_user", "test_password"))
                    .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                    .build();
        } else {
            final ClientAuthentication clientAuthentication = ClientAuthentication.ofBasic(SERVER_CREDENTIALS);
            final AccessTokenRequest accessTokenRequest =
                    AccessTokenRequest.ofResourceOwnerPassword("test_user", "test_password",
                                                               clientAuthentication, null);
            grant = OAuth2AuthorizationGrant
                    .builder(authClient, "/token/user/")
                    .accessTokenRequest(accessTokenRequest)
                    .build();
        }
        final WebClient client = WebClient.builder(resourceServer.httpUri())
                                          .decorator(OAuth2Client.newDecorator(grant))
                                          .build();

        assertThatThrownBy(() -> client.get("/resource-read-write/").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(InvalidClientException.class);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    public void testUnauthorized2(boolean useLegacyApi) {
        final WebClient authClient = WebClient.of(authServer.httpUri());

        final OAuth2AuthorizationGrant grant;
        if (useLegacyApi) {
            grant = OAuth2ResourceOwnerPasswordCredentialsGrant
                    .builder(authClient, "/token/user/")
                    .userCredentials(
                            () -> new AbstractMap.SimpleImmutableEntry<>("foo", "bar"))
                    .clientBasicAuthorization(() -> CLIENT_CREDENTIALS).build();
        } else {
            final ClientAuthentication clientAuthentication = ClientAuthentication.ofBasic(CLIENT_CREDENTIALS);
            final AccessTokenRequest accessTokenRequest =
                    AccessTokenRequest.ofResourceOwnerPassword("foo", "bar",
                                                               clientAuthentication, null);
            grant = OAuth2AuthorizationGrant.builder(authClient, "/token/user/")
                                            .accessTokenRequest(accessTokenRequest)
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
