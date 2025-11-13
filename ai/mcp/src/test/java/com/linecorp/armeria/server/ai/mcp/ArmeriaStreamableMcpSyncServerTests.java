/*
 * Copyright 2024-2024 the original author or authors.
 */

package com.linecorp.armeria.server.ai.mcp;

import com.linecorp.armeria.server.Server;

import io.modelcontextprotocol.server.AbstractMcpSyncServerTests;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

class ArmeriaStreamableMcpSyncServerTests extends AbstractMcpSyncServerTests {

    private static final String MESSAGE_ENDPOINT = "/mcp/message";

    private Server httpServer;

    private McpStreamableServerTransportProvider createMcpTransportProvider() {
        final ArmeriaStreamableServerTransportProvider transportProvider =
                ArmeriaStreamableServerTransportProvider.of();

        httpServer = Server.builder()
                           .service(MESSAGE_ENDPOINT, transportProvider.httpService())
                           .build();
        httpServer.start().join();
        return transportProvider;
    }

    @Override
    protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
        return McpServer.sync(createMcpTransportProvider());
    }

    @Override
    protected void onClose() {
        if (httpServer != null) {
            httpServer.stop();
        }
    }

}
