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
import java.util.concurrent.CompletionException;

import org.junit.Rule;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2AccessToken;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2ClientCredentialsService;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2IntrospectionService;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class OAuth2ClientCredentialsGrantTest {

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

    @Rule
    public ServerRule authServerRule = new ServerRule() {
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

    private final ServerRule resourceServerRule = new ServerRule(false) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final WebClient introspectClient = WebClient.of(authServerRule.httpUri());
            sb.service("/resource-read-write/",
                       OAuth2TokenIntrospectionAuthorizer.builder(introspectClient, "/introspect/token/")
                                                         .realm("protected resource read-write")
                                                         .accessTokenType("Bearer")
                                                         .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                                                         .permittedScope("read", "write")
                                                         .build().asAuthService(SERVICE));
            sb.service("/resource-read/",
                       OAuth2TokenIntrospectionAuthorizer.builder(introspectClient, "/introspect/token/")
                                                         .realm("protected resource read")
                                                         .accessTokenType("Bearer")
                                                         .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                                                         .permittedScope("read")
                                                         .build().asAuthService(SERVICE));
            sb.service("/resource-read-write-update/",
                       OAuth2TokenIntrospectionAuthorizer.builder(introspectClient, "/introspect/token/")
                                                         .realm("protected resource read-write-update")
                                                         .accessTokenType("Bearer")
                                                         .clientBasicAuthorization(() -> SERVER_CREDENTIALS)
                                                         .permittedScope("read", "write", "update")
                                                         .build().asAuthService(SERVICE));
        }
    };

    @Test
    public void testOk() throws Exception {
        try (Server resourceServer = resourceServerRule.start()) {

            final WebClient authClient = WebClient.of(authServerRule.httpUri());
            final OAuth2ClientCredentialsGrant grant = OAuth2ClientCredentialsGrant
                    .builder(authClient, "/token/client/")
                    .clientBasicAuthorization(() -> CLIENT_CREDENTIALS).build();

            final WebClient client = WebClient.builder(resourceServerRule.httpUri())
                                              .decorator(OAuth2Client.newDecorator(grant))
                                              .build();

            final AggregatedHttpResponse response1 = client.get("/resource-read-write/").aggregate().join();
            assertThat(response1.status()).isEqualTo(HttpStatus.OK);

            final AggregatedHttpResponse response2 = client.get("/resource-read/").aggregate().join();
            assertThat(response2.status()).isEqualTo(HttpStatus.OK);

            final AggregatedHttpResponse response3 =
                    client.get("/resource-read-write-update/").aggregate().join();
            assertThat(response3.status()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    public void testUnauthorized() {
        try (Server resourceServer = resourceServerRule.start()) {

            final WebClient authClient = WebClient.of(authServerRule.httpUri());
            final OAuth2ClientCredentialsGrant grant = OAuth2ClientCredentialsGrant
                    .builder(authClient, "/token/client/")
                    .clientBasicAuthorization(() -> SERVER_CREDENTIALS).build();

            final WebClient client = WebClient.builder(resourceServerRule.httpUri())
                                              .decorator(OAuth2Client.newDecorator(grant))
                                              .build();

            assertThatThrownBy(() -> client.get("/resource-read-write/").aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .getCause().isInstanceOf(InvalidClientException.class);
        }
    }
}
