/*
 * Copyright 2026 LY Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.athenz.AccessDeniedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * Ensures that a token client propagates the original cause when a token cannot be obtained
 * for a reason other than an authorization failure, such as a connection failure.
 */
class AthenzTokenClientErrorHandlingTest {

    @RegisterExtension
    static ServerExtension forbiddenZts = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // ZtsBaseClient appends "/zts/v1" to the base URI, so requests are served under that path.
            sb.service("/zts/v1/domain/testing/token",
                       (ctx, req) -> HttpResponse.of(HttpStatus.FORBIDDEN));
            sb.service("/zts/v1/oauth2/token",
                       (ctx, req) -> HttpResponse.of(HttpStatus.FORBIDDEN));
        }
    };

    private static final TlsKeyPair keyPair = TlsKeyPair.ofSelfSigned();

    private static ZtsBaseClient forbiddenZtsClient;
    private static ZtsBaseClient unreachableZtsClient;

    @BeforeAll
    static void beforeAll() {
        forbiddenZtsClient = ZtsBaseClient.builder(forbiddenZts.httpUri())
                                          .keyPair(() -> keyPair)
                                          .build();
        // Nothing listens on port 1. The response timeout bounds the whole retry ladder
        // so that the connection failure surfaces quickly.
        unreachableZtsClient = ZtsBaseClient.builder("http://127.0.0.1:1")
                                            .keyPair(() -> keyPair)
                                            .configureWebClient(cb -> cb.responseTimeoutMillis(2000))
                                            .build();
    }

    @AfterAll
    static void afterAll() {
        forbiddenZtsClient.close();
        unreachableZtsClient.close();
    }

    @Test
    void roleToken_shouldPropagateOriginalCauseWhenZtsIsUnreachable() {
        final RoleTokenClient client = newRoleTokenClient(unreachableZtsClient);
        final Throwable cause = catchThrowable(() -> client.getToken().join());
        assertThat(cause).isInstanceOf(CompletionException.class);
        assertThat(Throwables.getCausalChain(cause))
                .noneMatch(NullPointerException.class::isInstance)
                .noneMatch(AccessDeniedException.class::isInstance)
                .anyMatch(UnprocessedRequestException.class::isInstance);
    }

    @Test
    void accessToken_shouldPropagateOriginalCauseWhenZtsIsUnreachable() {
        final AccessTokenClient client = newAccessTokenClient(unreachableZtsClient);
        final Throwable cause = catchThrowable(() -> client.getToken().join());
        assertThat(cause).isInstanceOf(CompletionException.class);
        assertThat(Throwables.getCausalChain(cause))
                .noneMatch(NullPointerException.class::isInstance)
                .noneMatch(AccessDeniedException.class::isInstance)
                .anyMatch(UnprocessedRequestException.class::isInstance);
    }

    @Test
    void roleToken_shouldThrowAccessDeniedExceptionForForbiddenResponse() {
        final RoleTokenClient client = newRoleTokenClient(forbiddenZtsClient);
        final Throwable cause = catchThrowable(() -> client.getToken().join());
        assertThat(cause).isNotNull();
        assertThat(Throwables.getCausalChain(cause))
                .anyMatch(peeled -> {
                    return peeled instanceof AccessDeniedException &&
                           peeled.getMessage().equals(
                                   "Failed to obtain an Athenz role token. " +
                                   "(domain: testing, roles: test_role)");
                });
    }

    @Test
    void accessToken_shouldThrowAccessDeniedExceptionForForbiddenResponse() {
        final AccessTokenClient client = newAccessTokenClient(forbiddenZtsClient);
        final Throwable cause = catchThrowable(() -> client.getToken().join());
        assertThat(cause).isNotNull();
        assertThat(Throwables.getCausalChain(cause))
                .anyMatch(peeled -> {
                    return peeled instanceof AccessDeniedException &&
                           peeled.getMessage().equals(
                                   "Failed to obtain an Athenz access token. " +
                                   "(domain: testing, roles: test_role)");
                });
    }

    private static RoleTokenClient newRoleTokenClient(ZtsBaseClient ztsBaseClient) {
        return new RoleTokenClient(ztsBaseClient, "testing", ImmutableList.of("test_role"),
                                   Duration.ofMinutes(1), false);
    }

    private static AccessTokenClient newAccessTokenClient(ZtsBaseClient ztsBaseClient) {
        return new AccessTokenClient(ztsBaseClient, "testing", ImmutableList.of("test_role"),
                                     Duration.ofMinutes(1), false);
    }
}
