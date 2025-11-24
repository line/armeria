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

import com.fasterxml.jackson.databind.json.JsonMapper;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;

/**
 * A builder for creating an instance of {@link ArmeriaStatelessServerTransport}.
 */
@UnstableApi
public final class ArmeriaStatelessServerTransportBuilder {

    private McpJsonMapper jsonMapper = McpJsonMapper.getDefault();
    private McpTransportContextExtractor<ServiceRequestContext> contextExtractor =
            serverRequest -> McpTransportContext.EMPTY;

    ArmeriaStatelessServerTransportBuilder() {}

    /**
     * Sets the {@link JsonMapper} to use for JSON serialization/deserialization of MCP
     * messages.
     */
    public ArmeriaStatelessServerTransportBuilder jsonMapper(McpJsonMapper jsonMapper) {
        this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper");
        return this;
    }

    /**
     * Sets the {@link McpTransportContextExtractor} that allows providing the MCP feature
     * implementations to inspect HTTP transport level metadata that was present at
     * HTTP request processing time. This allows to extract custom headers and other
     * useful data for use during execution later on in the process.
     */
    public ArmeriaStatelessServerTransportBuilder contextExtractor(
            McpTransportContextExtractor<ServiceRequestContext> contextExtractor) {
        this.contextExtractor = requireNonNull(contextExtractor, "contextExtractor");
        return this;
    }

    /**
     * Builds a new instance of {@link ArmeriaStatelessServerTransport} with the configured settings.
     */
    public ArmeriaStatelessServerTransport build() {
        return new ArmeriaStatelessServerTransport(jsonMapper, contextExtractor);
    }
}
