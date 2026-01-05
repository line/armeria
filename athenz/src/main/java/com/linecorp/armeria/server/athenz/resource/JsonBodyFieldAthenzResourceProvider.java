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
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

final class JsonBodyFieldAthenzResourceProvider implements AthenzResourceProvider {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final JsonPointer jsonPointer;

    JsonBodyFieldAthenzResourceProvider(String jsonFieldName) {
        requireNonNull(jsonFieldName, "jsonFieldName");
        checkArgument(!jsonFieldName.isEmpty(), "jsonFieldName must not be empty");
        jsonPointer = JsonPointer.compile('/' + jsonFieldName.replace(".", "/"));
    }

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
            final JsonNode root = mapper.readTree(agg.content().array());
            final JsonNode node = root.at(jsonPointer);
            if (node.isMissingNode() || node.asText("").isEmpty()) {
                throw new AthenzResourceNotFoundException("JSON field not found for pointer: " + jsonPointer);
            }
            return node.asText("");
        } catch (Exception e) {
            throw new AthenzResourceNotFoundException("Failed to extract JSON field", e);
        }
    }
}
