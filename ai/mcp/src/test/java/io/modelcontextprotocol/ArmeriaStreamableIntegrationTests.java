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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ai.mcp.ArmeriaStreamableServerTransportProvider;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

@Timeout(15)
class ArmeriaStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

    private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";
    private static final int MAX_REQUEST_SIZE = 2048;

    private Server httpServer;

    private ArmeriaStreamableServerTransportProvider mcpStreamableServerTransportProvider;
    private McpStreamableServerTransportProvider sessionRecordingTransportProvider;
    private final AtomicReference<McpStreamableServerSession> sessionRef = new AtomicReference<>();

    static McpTransportContextExtractor<ServiceRequestContext> TEST_CONTEXT_EXTRACTOR =
            r -> McpTransportContext
                    .create(Map.of("important", "value"));

    static Stream<Arguments> clientsForTesting() {
        return Stream.of(Arguments.of("httpclient"));
    }

    @Override
    protected void prepareClients(int port, String mcpEndpoint) {
        clientBuilders.put("httpclient", McpClient.sync(HttpClientStreamableHttpTransport
                                                              .builder("http://localhost:" + port)
                                                              .endpoint(mcpEndpoint)
                                                              .build())
                                                .requestTimeout(Duration.ofHours(10)));
    }

    @Override
    protected void awaitClientStreamEstablished() {
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            final McpStreamableServerSession session = sessionRef.get();
            return session != null && session.hasListeningStream();
        });
    }

    @Test
    void waitsForListeningStream() throws Exception {
        final CompletableFuture<HttpResponse> listeningResponse = new CompletableFuture<>();
        final CompletableFuture<ServiceRequestContext> getReceived = new CompletableFuture<>();
        final Server delayedServer = Server.builder()
                .service(CUSTOM_MESSAGE_ENDPOINT, (ctx, req) -> {
                    if (req.method() == HttpMethod.GET) {
                        getReceived.complete(ctx);
                        return HttpResponse.of(listeningResponse);
                    }
                    return mcpStreamableServerTransportProvider.httpService().serve(ctx, req);
                })
                .build();
        delayedServer.start().join();
        prepareClients(delayedServer.activeLocalPort(), CUSTOM_MESSAGE_ENDPOINT);

        final var roots = List.of(Root.builder("uri1://").name("root1").build());
        final var rootsRef = new AtomicReference<List<Root>>();
        final var mcpServer = prepareSyncServerBuilder()
                .rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
                .build();
        try (var mcpClient = clientBuilders.get("httpclient")
                .capabilities(ClientCapabilities.builder().roots(true).build())
                .roots(roots)
                .build()) {
            mcpClient.initialize();
            final ServiceRequestContext getCtx = getReceived.get(5, TimeUnit.SECONDS);

            final CompletableFuture<Void> waitStarted = new CompletableFuture<>();
            final CompletableFuture<Void> streamReady = CompletableFuture.runAsync(() -> {
                assertThat(sessionRef.get().hasListeningStream()).isFalse();
                waitStarted.complete(null);
                awaitClientStreamEstablished();
            });
            try {
                waitStarted.get(5, TimeUnit.SECONDS);
                await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5))
                       .untilAsserted(() -> assertThat(streamReady).isNotDone());
            } finally {
                listeningResponse.complete(mcpStreamableServerTransportProvider.httpService()
                                                                              .serve(getCtx, getCtx.request()));
                streamReady.get(5, TimeUnit.SECONDS);
            }
            mcpClient.rootsListChangedNotification();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(rootsRef.get()).containsExactlyElementsOf(roots);
            });
        } finally {
            listeningResponse.cancel(false);
            mcpServer.closeGracefully();
            delayedServer.close();
        }
    }

    // Override to close the server before the client to avoid a race condition where the
    // HttpClientStreamableHttpTransport receives an unexpected response when the server
    // closes the connection mid-flight during the roots/list round-trip.
    @ParameterizedTest
    @MethodSource("clientsForTesting")
    @Override
    void testRootsServerCloseWithActiveSubscription(String clientType) {
        final var roots = List.of(Root.builder("uri1://").name("root1").build());
        final var rootsRef = new AtomicReference<List<Root>>();

        final var mcpServer = prepareSyncServerBuilder()
                .rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
                .build();

        try (var mcpClient = clientBuilders.get(clientType)
                .capabilities(ClientCapabilities.builder().roots(true).build())
                .roots(roots)
                .build()) {

            final var initResult = mcpClient.initialize();
            assertThat(initResult).isNotNull();
            awaitClientStreamEstablished();

            mcpClient.rootsListChangedNotification();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(rootsRef.get()).containsAll(roots);
            });

            // Close the server before the client to avoid a race condition.
            mcpServer.closeGracefully();
        }
    }

    @Test
    void rejectsOversizedRequest() throws Exception {
        final String inputSchema = """
                {
                    "type": "object",
                    "properties": { "message": { "type": "string" } },
                    "required": ["message"]
                }
                """;
        final var tool = McpServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder("tool1", McpJsonDefaults.getMapper(), inputSchema).build())
                .callHandler((exchange, request) -> CallToolResult.builder()
                        .addContent(TextContent.builder("ok").build())
                        .build())
                .build();
        final var mcpServer = prepareSyncServerBuilder().tools(tool).build();

        try (var mcpClient = clientBuilders.get("httpclient").build()) {
            mcpClient.initialize();
            assertThat(mcpClient.listTools().tools()).contains(tool.tool());

            final String oversizedBody = "a".repeat(MAX_REQUEST_SIZE + 1);
            assertThatThrownBy(() -> mcpClient.callTool(CallToolRequest.builder("tool1")
                    .arguments(Map.of("message", oversizedBody)).build()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("413");
        } finally {
            mcpServer.closeGracefully();
        }
    }

    @Override
    protected AsyncSpecification<?> prepareAsyncServerBuilder() {
        return McpServer.async(sessionRecordingTransportProvider);
    }

    @Override
    protected SyncSpecification<?> prepareSyncServerBuilder() {
        return McpServer.sync(sessionRecordingTransportProvider);
    }

    @BeforeEach
    public void before() {
        mcpStreamableServerTransportProvider =
                ArmeriaStreamableServerTransportProvider.builder()
                                                        .contextExtractor(TEST_CONTEXT_EXTRACTOR)
                                                        .build();
        sessionRecordingTransportProvider = mock(McpStreamableServerTransportProvider.class,
                                                 delegatesTo(mcpStreamableServerTransportProvider));
        doAnswer(invocation -> {
            final McpStreamableServerSession.Factory factory = invocation.getArgument(0);
            mcpStreamableServerTransportProvider.setSessionFactory(request -> {
                final var init = factory.startSession(request);
                sessionRef.set(init.session());
                return init;
            });
            return null;
        }).when(sessionRecordingTransportProvider).setSessionFactory(any());

        httpServer = Server.builder()
                           .maxRequestLength(MAX_REQUEST_SIZE)
                           .service(CUSTOM_MESSAGE_ENDPOINT, mcpStreamableServerTransportProvider.httpService())
                           .build();
        httpServer.start().join();
        prepareClients(httpServer.activeLocalPort(), CUSTOM_MESSAGE_ENDPOINT);
    }

    @AfterEach
    public void after() {
        if (httpServer != null) {
            httpServer.closeAsync();
        }
    }
}
