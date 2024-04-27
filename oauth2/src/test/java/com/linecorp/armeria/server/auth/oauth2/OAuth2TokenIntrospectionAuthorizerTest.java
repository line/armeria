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

package com.linecorp.armeria.server.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2AccessToken;
import com.linecorp.armeria.internal.server.auth.oauth2.MockOAuth2IntrospectionService;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class OAuth2TokenIntrospectionAuthorizerTest {

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
                                       .clientAuthentication(
                                               () -> ClientAuthentication.ofBasic(CLIENT_CREDENTIALS))
                                       .permittedScope("read", "write")
                                       .build()
                       ).build(SERVICE));
            sb.service("/resource-read/",
                       AuthService.builder().addOAuth2(
                               OAuth2TokenIntrospectionAuthorizer
                                       .builder(introspectClient, "/introspect/token/")
                                       .realm("protected resource read")
                                       .accessTokenType("Bearer")
                                       .clientAuthentication(
                                               ClientAuthentication.ofClientPassword("test_server",
                                                                                     "server_secret"))
                                       .permittedScope("read")
                                       .build()
                       ).build(SERVICE));
            sb.service("/resource-read-write-update/",
                       AuthService.builder().addOAuth2(
                               OAuth2TokenIntrospectionAuthorizer
                                       .builder(introspectClient, "/introspect/token/")
                                       .realm("protected resource read-write-update")
                                       .accessTokenType("Bearer")
                                       .clientAuthentication(ClientAuthentication.ofBasic(SERVER_CREDENTIALS))
                                       .permittedScope("read", "write", "update")
                                       .build()
                       ).build(SERVICE));
        }
    };

    @Test
    void testOk() throws Exception {
        final BlockingWebClient client = resourceServer.blockingWebClient();

        final RequestHeaders requestHeaders1 = RequestHeaders.of(
                HttpMethod.GET, "/resource-read-write/",
                HttpHeaderNames.AUTHORIZATION, "Bearer " + TOKEN.accessToken());
        final AggregatedHttpResponse response1 = client.execute(requestHeaders1);
        assertThat(response1.status()).isEqualTo(HttpStatus.OK);

        final RequestHeaders requestHeaders2 = RequestHeaders.of(
                HttpMethod.GET, "/resource-read/",
                HttpHeaderNames.AUTHORIZATION, "Bearer " + TOKEN.accessToken());
        final AggregatedHttpResponse response2 = client.execute(requestHeaders2);
        assertThat(response2.status()).isEqualTo(HttpStatus.OK);

        final RequestHeaders requestHeaders3 = RequestHeaders.of(
                HttpMethod.GET, "/resource-read-write-update/",
                HttpHeaderNames.AUTHORIZATION, "Bearer " + TOKEN.accessToken());
        final AggregatedHttpResponse response3 = client.execute(requestHeaders3);
        assertThat(response3.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void testUnauthorized() throws Exception {
        final BlockingWebClient client = resourceServer.blockingWebClient();

        final RequestHeaders requestHeaders1 = RequestHeaders.of(
                HttpMethod.GET, "/resource-read-write/",
                HttpHeaderNames.AUTHORIZATION, "Bearer XYZ");
        final AggregatedHttpResponse response1 = client.execute(requestHeaders1);
        assertThat(response1.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response1.headers().get(HttpHeaderNames.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"protected resource read-write\", " +
                           "error=\"invalid_token\", scope=\"read write\"");
        assertThat(response1.content().isEmpty()).isTrue();

        final RequestHeaders requestHeaders2 = RequestHeaders.of(
                HttpMethod.GET, "/resource-read-write/",
                HttpHeaderNames.AUTHORIZATION, "Basic " + CLIENT_CREDENTIALS);
        final AggregatedHttpResponse response2 = client.execute(requestHeaders2);
        assertThat(response2.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response2.headers().get(HttpHeaderNames.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"protected resource read-write\", scope=\"read write\"");
        assertThat(response2.content().isEmpty()).isTrue();
    }
}
