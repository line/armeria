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
 *
 */

package com.linecorp.armeria.server.athenz.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.concurrent.CompletableFuture;

/**
 * Provides the Athenz resource string from a specific field in the JSON request body.
 *
 * <p>This provider parses the request body as JSON and extracts the resource value from the field
 * specified by {@code jsonFieldName}. If the content type is not JSON, the field does not exist,
 * or parsing fails, an empty string is returned.
 *
 * <p>Example:
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper();
 * AthenzResourceProvider provider = new JsonBodyFieldAthenzResourceProvider(mapper, "resourceId");
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(provider)
 *              .action("write")
 *              .newDecorator();
 * }</pre>
 *
 * <p>Request body example:
 * <pre>{@code
 * {
 *   "resourceId": "myResource",
 *   "data": "..."
 * }
 * }</pre>
 */
public class JsonBodyFieldAthenzResourceProvider implements AthenzResourceProvider {

    private final ObjectMapper objectMapper;

    private final String jsonFieldName;

    /**
     * Creates a new instance that extracts the Athenz resource from the specified JSON field.
     *
     * @param objectMapper the {@link ObjectMapper} used to parse the JSON request body
     * @param jsonFieldName the name of the JSON field to extract the resource from
     */
    public JsonBodyFieldAthenzResourceProvider(ObjectMapper objectMapper, String jsonFieldName) {
        this.objectMapper = objectMapper;
        this.jsonFieldName = jsonFieldName;
    }

    @Override
    public CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req) {
        return req.aggregate().thenApply(this::extractFieldSafely);
    }

    private String extractFieldSafely(AggregatedHttpRequest agg) {
        final MediaType contentType = agg.contentType();
        if (contentType == null || !contentType.is(MediaType.JSON)) {
            return "";
        }
        try {
            final JsonNode root = objectMapper.readTree(agg.contentUtf8());
            final JsonNode node = root.get(jsonFieldName);
            return node != null ? node.asText("") : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
