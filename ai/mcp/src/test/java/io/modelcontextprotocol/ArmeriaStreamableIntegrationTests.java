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

package io.modelcontextprotocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ai.mcp.ArmeriaStreamableServerTransportProvider;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Root;

@Timeout(15)
class ArmeriaStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

    private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

    private Server httpServer;

    private ArmeriaStreamableServerTransportProvider mcpStreamableServerTransportProvider;

    static McpTransportContextExtractor<ServiceRequestContext> TEST_CONTEXT_EXTRACTOR =
            r -> McpTransportContext
                    .create(Map.of("important", "value"));

    @Override
    protected McpClient.SyncSpec getMcpClientBuilder() {
        return McpClient.sync(HttpClientStreamableHttpTransport
                                      .builder("http://localhost:" + httpServer.activeLocalPort())
                                      .endpoint(CUSTOM_MESSAGE_ENDPOINT)
                                      .build())
                        .requestTimeout(Duration.ofHours(10));
    }

    // Override to close the server before the client to avoid a race condition where the
    // HttpClientStreamableHttpTransport receives an unexpected response when the server
    // closes the connection mid-flight during the roots/list round-trip.
    @Test
    @Override
    void testRootsServerCloseWithActiveSubscription() {
        final var roots = List.of(Root.builder("uri1://").name("root1").build());
        final var rootsRef = new AtomicReference<List<Root>>();

        final var mcpServer = prepareSyncServerBuilder()
                .rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
                .build();

        try (var mcpClient = getMcpClientBuilder()
                .capabilities(ClientCapabilities.builder().roots(true).build())
                .roots(roots)
                .build()) {

            final var initResult = mcpClient.initialize();
            assertThat(initResult).isNotNull();

            mcpClient.rootsListChangedNotification();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(rootsRef.get()).containsAll(roots);
            });

            // Close the server before the client to avoid a race condition.
            mcpServer.closeGracefully();
        }
    }

    @Override
    protected AsyncSpecification<?> prepareAsyncServerBuilder() {
        return McpServer.async(mcpStreamableServerTransportProvider);
    }

    @Override
    protected SyncSpecification<?> prepareSyncServerBuilder() {
        return McpServer.sync(mcpStreamableServerTransportProvider);
    }

    @BeforeEach
    public void before() {
        mcpStreamableServerTransportProvider =
                ArmeriaStreamableServerTransportProvider.builder()
                                                        .contextExtractor(TEST_CONTEXT_EXTRACTOR)
                                                        .build();

        httpServer = Server.builder()
                           .maxRequestLength(MAX_REQUEST_SIZE)
                           .service(CUSTOM_MESSAGE_ENDPOINT, mcpStreamableServerTransportProvider.httpService())
                           .build();
        httpServer.start().join();
    }

    @AfterEach
    public void after() {
        if (httpServer != null) {
            httpServer.stop().join();
        }
    }
}
