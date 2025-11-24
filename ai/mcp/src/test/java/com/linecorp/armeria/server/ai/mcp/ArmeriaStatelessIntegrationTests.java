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

package com.linecorp.armeria.server.ai.mcp;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.web.reactive.function.client.WebClient;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.logging.LoggingService;

import io.modelcontextprotocol.AbstractStatelessIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;

@Timeout(15)
class ArmeriaStatelessIntegrationTests extends AbstractStatelessIntegrationTests {

    private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

    private Server httpServer;

    private ArmeriaStatelessServerTransport mcpStreamableServerTransport;

    static Stream<Arguments> clientsForTesting() {
        return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
    }

    @Override
    protected void prepareClients(int port, String mcpEndpoint) {
        clientBuilders
                .put("httpclient",
                     McpClient.sync(HttpClientStreamableHttpTransport
                                            .builder("http://127.0.0.1:" + port)
                                            .endpoint(CUSTOM_MESSAGE_ENDPOINT)
                                            .build())
                              .initializationTimeout(Duration.ofHours(10))
                              .requestTimeout(Duration.ofHours(10)));
        // TODO(ikhoon): Implement Armeria-based McpClient
        clientBuilders
                .put("webflux", McpClient
                        .sync(WebClientStreamableHttpTransport
                                      .builder(WebClient.builder().baseUrl("http://127.0.0.1:" + port))
                                      .endpoint(CUSTOM_MESSAGE_ENDPOINT)
                                      .build())
                        .initializationTimeout(Duration.ofHours(10))
                        .requestTimeout(Duration.ofHours(10)));
    }

    @Override
    protected StatelessAsyncSpecification prepareAsyncServerBuilder() {
        return McpServer.async(mcpStreamableServerTransport);
    }

    @Override
    protected StatelessSyncSpecification prepareSyncServerBuilder() {
        return McpServer.sync(mcpStreamableServerTransport);
    }

    @BeforeEach
    public void before() {
        mcpStreamableServerTransport = ArmeriaStatelessServerTransport.of();

        httpServer = Server.builder()
                           .service(CUSTOM_MESSAGE_ENDPOINT, mcpStreamableServerTransport.httpService())
                           .decorator(LoggingService.newDecorator())
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
