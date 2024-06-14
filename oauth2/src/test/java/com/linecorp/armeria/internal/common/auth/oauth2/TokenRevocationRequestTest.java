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

package com.linecorp.armeria.internal.common.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.AbstractMap.SimpleImmutableEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.oauth2.MockOAuth2AccessToken;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class TokenRevocationRequestTest {

    static final String CLIENT_CREDENTIALS = "dGVzdF9jbGllbnQ6Y2xpZW50X3NlY3JldA=="; //test_client:client_secret
    static final String SERVER_CREDENTIALS = "dGVzdF9zZXJ2ZXI6c2VydmVyX3NlY3JldA=="; //test_server:server_secret
    static final MockOAuth2AccessToken token =
        MockOAuth2AccessToken.generate("test_client", null, Duration.ofHours(3L),
                                       ImmutableMap.of("extension_field", "twenty-seven"), "read", "write");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/revoke", new MockOAuth2RevocationService()
                    .withAuthorizedClient("test_client", "client_secret")
                    .withAuthorizedClient("test_server", "server_secret")
                    .withClientToken("test_client", token));
        }
    };

    @Test
    public void testRevoke() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final RequestHeaders requestHeaders1 = RequestHeaders.of(
                HttpMethod.POST, "/revoke/token/",
                HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA,
                HttpHeaderNames.ACCEPT, MediaType.JSON,
                HttpHeaderNames.AUTHORIZATION, "Basic " + SERVER_CREDENTIALS);
        final AggregatedHttpResponse response1 = client.execute(
                requestHeaders1, "token=" + token.grantedToken().accessToken() +
                                 "&token_type_hint=access_token").aggregate().join();
        assertThat(response1.status()).isEqualTo(HttpStatus.OK);
        assertThat(response1.contentUtf8()).isEqualTo(HttpStatus.OK.toString());

        final RequestHeaders requestHeaders2 = RequestHeaders.of(
                HttpMethod.POST, "/revoke/token/",
                HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA,
                HttpHeaderNames.ACCEPT, MediaType.JSON,
                HttpHeaderNames.AUTHORIZATION, "Basic " + SERVER_CREDENTIALS);
        final AggregatedHttpResponse response2 = client.execute(
                requestHeaders2, "token=ABC&token_type_hint=access_token").aggregate().join();
        assertThat(response2.status()).isEqualTo(HttpStatus.OK);
        assertThat(response2.contentUtf8()).isEqualTo(HttpStatus.OK.toString());

        final RequestHeaders requestHeaders3 = RequestHeaders.of(
                HttpMethod.POST, "/revoke/token/",
                HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA,
                HttpHeaderNames.ACCEPT, MediaType.JSON,
                HttpHeaderNames.AUTHORIZATION, "Basic " + CLIENT_CREDENTIALS);
        final AggregatedHttpResponse response3 = client.execute(
                requestHeaders3, "token=" + token.grantedToken().accessToken() +
                                 "&token_type_hint=access_token").aggregate().join();
        assertThat(response3.status()).isEqualTo(HttpStatus.OK);
        assertThat(response3.contentUtf8()).isEqualTo(HttpStatus.OK.toString());
    }

    @Test
    public void testAuthError() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final RequestHeaders requestHeaders1 = RequestHeaders.of(
                HttpMethod.POST, "/revoke/token/",
                HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA,
                HttpHeaderNames.ACCEPT, MediaType.JSON);
        final AggregatedHttpResponse response1 = client.execute(
                requestHeaders1, "token=" + token.grantedToken().accessToken() +
                                 "&token_type_hint=access_token").aggregate().join();
        assertThat(response1.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response1.headers())
                .contains(new SimpleImmutableEntry<>(
                        HttpHeaderNames.WWW_AUTHENTICATE, "Basic realm=\"token revocation\""));

        final RequestHeaders requestHeaders2 = RequestHeaders.of(
                HttpMethod.POST, "/revoke/token/",
                HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA,
                HttpHeaderNames.ACCEPT, MediaType.JSON,
                HttpHeaderNames.AUTHORIZATION, "Basic");
        final AggregatedHttpResponse response2 = client.execute(
                requestHeaders2, "token=" + token.grantedToken().accessToken() +
                                 "&token_type_hint=access_token").aggregate().join();
        assertThat(response2.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response2.headers())
                .contains(new SimpleImmutableEntry<>(
                        HttpHeaderNames.WWW_AUTHENTICATE, "Basic realm=\"token revocation\""));

        final RequestHeaders requestHeaders3 = RequestHeaders.of(
                HttpMethod.POST, "/revoke/token/",
                HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA,
                HttpHeaderNames.ACCEPT, MediaType.JSON,
                HttpHeaderNames.AUTHORIZATION, "Basic Zm9vOmJhcg=="); // foo:bar
        final AggregatedHttpResponse response3 = client.execute(
                requestHeaders3, "token=" + token.grantedToken().accessToken() +
                                 "&token_type_hint=access_token").aggregate().join();
        assertThat(response3.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response3.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(response3.contentUtf8()).isEqualTo("{\"error\":\"invalid_client\"}");
    }

    @Test
    public void testError() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final RequestHeaders requestHeaders1 = RequestHeaders.of(
                HttpMethod.POST, "/revoke/token/",
                HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA,
                HttpHeaderNames.ACCEPT, MediaType.JSON,
                HttpHeaderNames.AUTHORIZATION, "Basic " + SERVER_CREDENTIALS);
        final AggregatedHttpResponse response1 = client.execute(
                requestHeaders1, "token_type_hint=access_token").aggregate().join();
        assertThat(response1.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response1.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(response1.contentUtf8()).isEqualTo("{\"error\":\"invalid_request\"}");
    }
}
