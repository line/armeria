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

package com.linecorp.armeria.common.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.auth.oauth2.AccessTokenRequest;
import com.linecorp.armeria.client.auth.oauth2.OAuth2AuthorizationGrant;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class OAuth2RefreshBeforeTest {
    private static final AtomicInteger refreshCount = new AtomicInteger();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/token", (ctx, req) -> {
                final int count = refreshCount.getAndIncrement();
                final GrantedOAuth2AccessToken token =
                        GrantedOAuth2AccessToken.builder("token" + count)
                                                .expiresIn(Duration.ofSeconds(10))
                                                .build();
                final HttpResponse response = HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                                                              token.rawResponse());
                if (count == 0) {
                    return response;
                } else {
                    // Delay the response to check the cached value is used and the refresh is triggered.
                    return HttpResponse.delayed(response, Duration.ofSeconds(5));
                }
            });
        }
    };

    @BeforeEach
    void setUp() {
        refreshCount.set(0);
    }

    @Test
    void shouldRefreshBeforeExpiry() throws InterruptedException {
        final AccessTokenRequest accessTokenRequest =
                AccessTokenRequest.ofClientCredentials("client_id", "client_secret");
        final OAuth2AuthorizationGrant grant =
                OAuth2AuthorizationGrant.builder(server.webClient(cb -> {
                                            cb.decorator(LoggingClient.newDecorator());
                                        }), "/token")
                                        .refreshBefore(Duration.ofSeconds(5))
                                        .accessTokenRequest(accessTokenRequest)
                                        .build();

        assertThat(grant.getAccessToken().toCompletableFuture().join().accessToken())
                .isEqualTo("token0");
        // Drain the first request.
        server.requestContextCaptor().take();
        // The first token should be used.
        assertThat(grant.getAccessToken().toCompletableFuture().join().accessToken())
                .isEqualTo("token0");
        assertThat(server.requestContextCaptor().isEmpty()).isTrue();
        Thread.sleep(5000);
        // The first token should still be used;
        assertThat(grant.getAccessToken().toCompletableFuture().join().accessToken())
                .isEqualTo("token0");
        // But the refresh should be triggered.
        Thread.sleep(1000);
        assertThat(server.requestContextCaptor().size()).isOne();

        assertThat(grant.getAccessToken().toCompletableFuture().join().accessToken())
                .isEqualTo("token0");
        // Make sure the refresh is triggered only once.
        assertThat(server.requestContextCaptor().size()).isOne();
        // The refresh response will be sent after 5 seconds.
        Thread.sleep(5000);
        assertThat(grant.getAccessToken().toCompletableFuture().join().accessToken())
                .isEqualTo("token1");
    }
}
