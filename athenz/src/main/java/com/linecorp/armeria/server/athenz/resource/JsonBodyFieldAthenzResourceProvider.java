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
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

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
 * AthenzResourceProvider provider = new JsonBodyFieldAthenzResourceProvider(mapper, resourceId);
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(provider, resourceTagValue)
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
@UnstableApi
public class JsonBodyFieldAthenzResourceProvider implements AthenzResourceProvider {

    private static final Logger logger = LoggerFactory.getLogger(JsonBodyFieldAthenzResourceProvider.class);

    private final ObjectMapper objectMapper;

    private final String jsonFieldName;

    /**
     * Creates a new instance that extracts the Athenz resource from the specified JSON field.
     *
     * @param objectMapper  the {@link ObjectMapper} used to parse the JSON request body
     * @param jsonFieldName the name of the JSON field to extract the resource from
     */
    public JsonBodyFieldAthenzResourceProvider(ObjectMapper objectMapper, String jsonFieldName) {
        requireNonNull(objectMapper, "objectMapper");
        requireNonNull(jsonFieldName, "jsonFieldName");
        checkArgument(!jsonFieldName.isEmpty(), "jsonFieldName must not be empty");
        this.objectMapper = objectMapper;
        this.jsonFieldName = jsonFieldName;
    }

    @Override
    public CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req) {
        final MediaType contentType = req.contentType();
        if (contentType == null || !contentType.is(MediaType.JSON)) {
            return CompletableFuture.completedFuture("");
        }
        return req.aggregate().thenApply(this::extractFieldSafely);
    }

    private String extractFieldSafely(AggregatedHttpRequest agg) {
        try {
            final JsonNode root = objectMapper.readTree(agg.contentUtf8());
            final JsonNode node = root.get(jsonFieldName);
            if (node == null) {
                logger.debug("JSON field '{}' not found in request body", jsonFieldName);
                return "";
            }
            return node.asText("");
        } catch (Exception e) {
            logger.debug("Failed to extract resource from JSON field '{}': {}", jsonFieldName, e.getMessage(), e);
            return "";
        }
    }
}
