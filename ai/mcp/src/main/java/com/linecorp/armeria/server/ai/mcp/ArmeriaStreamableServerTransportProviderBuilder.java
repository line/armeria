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

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.server.ServiceRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;

/**
 * A builder which creates a {@link ArmeriaStreamableServerTransportProvider}.
 */
public final class ArmeriaStreamableServerTransportProviderBuilder {

    private McpJsonMapper jsonMapper = McpJsonMapper.getDefault();
    private McpTransportContextExtractor<ServiceRequestContext> contextExtractor =
            ctx -> McpTransportContext.EMPTY;
    private boolean disallowDelete;
    @Nullable
    private Duration keepAliveInterval;

    ArmeriaStreamableServerTransportProviderBuilder() {}

    /**
     * Sets the {@link McpJsonMapper} to use for JSON serialization/deserialization of MCP messages.
     */
    public ArmeriaStreamableServerTransportProviderBuilder jsonMapper(McpJsonMapper jsonMapper) {
        this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper");
        return this;
    }

    /**
     * Sets the context extractor that allows providing the MCP feature
     * implementations to inspect HTTP transport level metadata that was present at
     * HTTP request processing time. This allows to extract custom headers and other
     * useful data for use during execution later on in the process.
     */
    public ArmeriaStreamableServerTransportProviderBuilder contextExtractor(
            McpTransportContextExtractor<ServiceRequestContext> contextExtractor) {
        this.contextExtractor = requireNonNull(contextExtractor, "contextExtractor");
        return this;
    }

    /**
     * Sets whether the session removal capability is disabled.
     *
     * @param disallowDelete if {@code true}, the DELETE endpoint will not be supported and
     *                       sessions won't be deleted.
     */
    public ArmeriaStreamableServerTransportProviderBuilder disallowDelete(boolean disallowDelete) {
        this.disallowDelete = disallowDelete;
        return this;
    }

    /**
     * Sets the keep-alive interval for the server transport.
     * By default, no keep-alive will be scheduled.
     *
     * @param keepAliveInterval The interval for sending keep-alive messages.
     */
    public ArmeriaStreamableServerTransportProviderBuilder keepAliveInterval(Duration keepAliveInterval) {
        this.keepAliveInterval = requireNonNull(keepAliveInterval, "keepAliveInterval");
        return this;
    }

    /**
     * Returns a new instance of {@link ArmeriaStreamableServerTransportProvider} with the configured settings.
     */
    public ArmeriaStreamableServerTransportProvider build() {
        return new ArmeriaStreamableServerTransportProvider(jsonMapper, contextExtractor, disallowDelete,
                                                            keepAliveInterval);
    }
}
