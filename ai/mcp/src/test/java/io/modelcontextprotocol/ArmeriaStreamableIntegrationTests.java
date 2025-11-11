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

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.reactive.function.client.WebClient;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ai.mcp.ArmeriaStreamableServerTransportProvider;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

@Timeout(15)
class ArmeriaStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

    private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

    private Server httpServer;

    private ArmeriaStreamableServerTransportProvider mcpStreamableServerTransportProvider;

    static McpTransportContextExtractor<ServiceRequestContext> TEST_CONTEXT_EXTRACTOR =
            r -> McpTransportContext
                    .create(Map.of("important", "value"));

    static Stream<Arguments> clientsForTesting() {
        return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
    }

    @Override
    protected void prepareClients(int port, String mcpEndpoint) {
        clientBuilders
                .put("httpclient",
                     McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                                                                     .endpoint(CUSTOM_MESSAGE_ENDPOINT)
                                                                     .build())
                              .requestTimeout(Duration.ofHours(10)));
        // TODO(ikhoon): Implement Armeria-based McpClient
        clientBuilders.put("webflux",
                           McpClient.sync(WebClientStreamableHttpTransport
                                                  .builder(WebClient.builder()
                                                                    .baseUrl("http://localhost:" + port))
                                                  .endpoint(CUSTOM_MESSAGE_ENDPOINT)
                                                  .build())
                                    .requestTimeout(Duration.ofHours(10)));
    }

    @ParameterizedTest(name = "{0} : {displayName} ")
    @MethodSource("clientsForTesting")
    @Override
    void testCreateElicitationSuccess(String clientType) {
        final var clientBuilder = clientBuilders.get(clientType);

        final Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
            assertThat(request.message()).isNotEmpty();
            assertThat(request.requestedSchema()).isNotNull();
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
                                              Map.of("message", request.message()));
        };

        final CallToolResult callResponse =
                McpSchema.CallToolResult.builder()
                                        .addContent(new McpSchema.TextContent("CALL RESPONSE"))
                                        .build();

        final McpServerFeatures.AsyncToolSpecification tool =
                McpServerFeatures.AsyncToolSpecification
                        .builder()
                        .tool(Tool.builder().name("tool1").description("tool1 description")
                                  .inputSchema(EMPTY_JSON_SCHEMA).build())
                        .callHandler((exchange, request) -> {
                            final ElicitRequest elicitationRequest =
                                    McpSchema.ElicitRequest
                                            .builder()
                                            .message("Test message")
                                            .requestedSchema(
                                                    Map.of("type", "object", "properties",
                                                           Map.of("message", Map.of("type", "string"))))
                                            .build();

                            // The upstream test code uses StepVerifier to verify the elicitation response which
                            // blocks Armeria event loop and leads a deadlock. To avoid this, we use doOnNext to
                            // perform assertions instead of StepVerifier.
                            // TODO(ikhoon): Open a pull request to the upstream to fix the test.
                            return exchange.createElicitation(elicitationRequest).doOnNext(result -> {
                                               assertThat(result).isNotNull();
                                               assertThat(result.action()).isEqualTo(
                                                       McpSchema.ElicitResult.Action.ACCEPT);
                                               assertThat(result.content().get("message")).isEqualTo("Test message");
                                           })
                                           .thenReturn(callResponse);
                        })
                        .build();

        final McpAsyncServer mcpServer =
                prepareAsyncServerBuilder()
                        .serverInfo("test-server", "1.0.0")
                        .tools(tool)
                        .build();

        try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
                                                    .capabilities(ClientCapabilities.builder().elicitation().build())
                                                    .elicitation(elicitationHandler)
                                                    .build()) {

            final InitializeResult initResult = mcpClient.initialize();
            assertThat(initResult).isNotNull();

            final CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

            assertThat(response).isNotNull();
            assertThat(response).isEqualTo(callResponse);
        } finally {
            mcpServer.closeGracefully().block();
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
                           .service(CUSTOM_MESSAGE_ENDPOINT, mcpStreamableServerTransportProvider.httpService())
                           .build();
        httpServer.start().join();

        prepareClients(httpServer.activeLocalPort(), null);
    }

    @AfterEach
    public void after() {
        if (httpServer != null) {
            httpServer.closeAsync();
        }
    }
}
