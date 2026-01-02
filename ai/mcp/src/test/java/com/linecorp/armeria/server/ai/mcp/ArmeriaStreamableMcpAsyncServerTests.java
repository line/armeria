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

import com.linecorp.armeria.server.Server;

import io.modelcontextprotocol.server.AbstractMcpAsyncServerTests;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

class ArmeriaStreamableMcpAsyncServerTests extends AbstractMcpAsyncServerTests {

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
    protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder() {
        return McpServer.async(createMcpTransportProvider());
    }

    @Override
    protected void onClose() {
        if (httpServer != null) {
            httpServer.closeAsync();
        }
    }
}
