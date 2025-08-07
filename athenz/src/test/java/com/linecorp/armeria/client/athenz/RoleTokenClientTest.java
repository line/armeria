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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.yahoo.athenz.zts.RoleToken;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RoleTokenClientTest {

    private static final AtomicReference<RoleToken> roleTokenRef = new AtomicReference<>();
    private static final AtomicInteger requestCount = new AtomicInteger();

    @RegisterExtension
    static ServerExtension mockServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/zts/v1/domain/{domainName}/token", (ctx, req) -> {
                requestCount.incrementAndGet();
                return HttpResponse.ofJson(roleTokenRef.get());
            });
        }
    };
    private static ZtsBaseClient ztsBaseClient;

    @BeforeAll
    static void beforeAll() {
        final TlsKeyPair tlsKeyPair = TlsKeyPair.ofSelfSigned();
        ztsBaseClient = ZtsBaseClient.builder(mockServer.httpUri())
                                     .keyPair(() -> tlsKeyPair)
                                     .build();
    }

    @AfterAll
    static void afterAll() {
        ztsBaseClient.close();
    }

    @BeforeEach
    void setUp() {
        requestCount.set(0);
    }

    @Test
    void shouldCacheTokenBeforeExpiry() throws Exception {
        final RoleTokenClient roleTokenClient = new RoleTokenClient(ztsBaseClient, "test",
                                                                    ImmutableList.of("role1", "role2"),
                                                                    Duration.ofSeconds(10));
        final RoleToken roleToken = new RoleToken();
        roleToken.setToken("test-token");
        roleToken.setExpiryTime(Instant.now().plusSeconds(100).toEpochMilli());
        roleTokenRef.set(roleToken);
        final String token0 = roleTokenClient.getToken().join();
        assertThat(token0).isEqualTo("test-token");
        final String token1 = roleTokenClient.getToken().join();
        assertThat(token1).isEqualTo(token0);
        Thread.sleep(1000);
        final String token2 = roleTokenClient.getToken().join();
        assertThat(token2).isEqualTo(token1);
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void shouldRefreshTokenBeforeExpiry() throws Exception {
        final TlsKeyPair tlsKeyPair = TlsKeyPair.ofSelfSigned();
        try (ZtsBaseClient ztsBaseClient = ZtsBaseClient.builder(mockServer.httpUri())
                                                        .keyPair(() -> tlsKeyPair)
                                                        .build()) {
            final RoleTokenClient roleTokenClient = new RoleTokenClient(ztsBaseClient, "test",
                                                                        ImmutableList.of("role1", "role2"),
                                                                        Duration.ofSeconds(10));
            final RoleToken roleToken = new RoleToken();
            roleToken.setToken("test-token");
            roleToken.setExpiryTime(Instant.now().plusSeconds(5).getEpochSecond());
            roleTokenRef.set(roleToken);
            final String token0 = roleTokenClient.getToken().join();
            assertThat(token0).isEqualTo("test-token");
            roleToken.setToken("test-token1");
            roleToken.setExpiryTime(Instant.now().plusSeconds(5).getEpochSecond());
            // Should return the cached token immediately and refresh it in the background.
            assertThat(roleTokenClient.getToken().join()).isEqualTo(token0);
            await().untilAtomic(requestCount, Matchers.is(2));
        }
    }
}
