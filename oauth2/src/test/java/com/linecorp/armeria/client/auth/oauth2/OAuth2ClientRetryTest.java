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

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedResponseException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class OAuth2ClientRetryTest {

    private static AtomicInteger tokenCounter = new AtomicInteger();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/token", (ctx, req) -> {
                if (tokenCounter.incrementAndGet() < 2) {
                    return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                }
                final GrantedOAuth2AccessToken token = GrantedOAuth2AccessToken.builder("token")
                                                                               .build();
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON, token.rawResponse());
            });
            sb.service("/resource", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @Test
    void retryOnFailure() {
        final AccessTokenRequest accessTokenRequest =
                AccessTokenRequest.ofClientCredentials("client_id", "client_secret");
        final WebClient authClient = server.webClient();
        final OAuth2AuthorizationGrant grant =
                OAuth2AuthorizationGrant.builder(authClient, "/token")
                                        .accessTokenRequest(accessTokenRequest)
                                        .build();

        // INTERNAL_SERVER_ERROR from the token server will raise UnsupportedMediaTypeException
        final RetryRule retryRule = RetryRule.onException(UnsupportedResponseException.class);
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(OAuth2Client.newDecorator(grant))
                                          .decorator(RetryingClient.newDecorator(retryRule))
                                          .build();
        final AggregatedHttpResponse response =
                client.prepare()
                      .get("/resource")
                      // Response streaming type uses RequestLog in which RetryingClient is used triggered a
                      // deadlock.
                      .exchangeType(ExchangeType.RESPONSE_STREAMING)
                      .execute()
                      .aggregate()
                      .join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }
}
