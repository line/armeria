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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.athenz.AthenzResourceNotFoundException;

/**
 * Provides the Athenz resource string from a specific field in the JSON request body.
 *
 * <p>This provider parses the request body as JSON and extracts the resource value from the field
 * specified by a JSON pointer. If the content type is not JSON, the field does not exist,
 * or parsing fails, an empty string is returned and the error is logged at debug level.
 *
 * <p>The provider supports both simple field names and nested paths using JSON Pointer notation:
 * <ul>
 *   <li>Simple field: {@code "resourceId"} → {@code /resourceId}</li>
 *   <li>Nested field: {@code "user.id"} → {@code /user/id}</li>
 * </ul>
 *
 * <p>Example with simple field name:
 * <pre>{@code
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(AthenzResourceProvider.ofJsonField("resourceId"))
 *              .action("write")
 *              .newDecorator();
 * }</pre>
 *
 * <p>Example with JSON pointer:
 * <pre>{@code
 * JsonPointer pointer = JsonPointer.compile("/user/id");
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(AthenzResourceProvider.ofJsonField(pointer))
 *              .action("write")
 *              .newDecorator();
 * }</pre>
 *
 * <p>Request body example:
 * <pre>{@code
 * {
 *   "resourceId": "myResource",
 *   "user": {
 *     "id": "123"
 *   }
 * }
 * }</pre>
 */
@UnstableApi
final class JsonBodyFieldAthenzResourceProvider implements AthenzResourceProvider {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final JsonPointer jsonPointer;

    /**
     * Creates a new instance that extracts the Athenz resource from the specified JSON field.
     *
     * @param jsonFieldName the name of the JSON field to extract the resource from
     */
    JsonBodyFieldAthenzResourceProvider(String jsonFieldName) {
        requireNonNull(jsonFieldName, "jsonFieldName");
        checkArgument(!jsonFieldName.isEmpty(), "jsonFieldName must not be empty");
        jsonPointer = JsonPointer.compile('/' + jsonFieldName.replace(".", "/"));
    }

    /**
     * Creates a new instance that extracts the Athenz resource from the specified JSON pointer.
     *
     * @param jsonPointer the JSON pointer to extract the resource from
     */
    JsonBodyFieldAthenzResourceProvider(JsonPointer jsonPointer) {
        requireNonNull(jsonPointer, "jsonPointer");
        this.jsonPointer = jsonPointer;
    }

    @Override
    public CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req) {
        final MediaType contentType = req.contentType();
        if (contentType == null || !contentType.is(MediaType.JSON)) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(
                    new AthenzResourceNotFoundException("Unsupported media type. Only " +
                                                        "application/json are supported"));
        }
        return req.aggregate().thenApply(this::extractFieldSafely);
    }

    private String extractFieldSafely(AggregatedHttpRequest agg) {
        try {
            final JsonNode root = mapper.readTree(agg.contentUtf8());
            final JsonNode node = root.at(jsonPointer);
            if (node.isMissingNode()) {
                throw new AthenzResourceNotFoundException("JSON field not found for pointer: " + jsonPointer);
            }
            return node.asText("");
        } catch (Exception e) {
            throw new AthenzResourceNotFoundException("Failed to extract JSON field", e);
        }
    }
}
