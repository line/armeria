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

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.ServiceRequestContext;

final class JsonBodyFieldAthenzResourceProvider implements AthenzResourceProvider {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final JsonPointer jsonPointer;

    JsonBodyFieldAthenzResourceProvider(String jsonPointerExpr) {
        requireNonNull(jsonPointerExpr, "jsonPointerExpr");
        checkArgument(!jsonPointerExpr.isEmpty(), "jsonPointerExpr must not be empty");
        checkArgument(jsonPointerExpr.charAt(0) == '/', "jsonPointerExpr must start with '/'");
        jsonPointer = JsonPointer.compile(jsonPointerExpr);
    }

    JsonBodyFieldAthenzResourceProvider(JsonPointer jsonPointer) {
        requireNonNull(jsonPointer, "jsonPointer");
        this.jsonPointer = jsonPointer;
    }

    @Override
    public CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req) {
        final MediaType contentType = req.contentType();
        if (contentType == null || !contentType.is(MediaType.JSON)) {
            // Throwing HttpResponseException will be okay because this is in a server module.
            // Will introduce a new Exception type later if needed.
            throw HttpResponseException.of(
                    HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                    "Content-Type must be application/json"));
        }
        return req.aggregate().thenApply(this::extractFieldSafely);
    }

    private String extractFieldSafely(AggregatedHttpRequest agg) {
        final JsonNode root;
        try {
            root = mapper.readTree(agg.content().array());
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse JSON body", e);
        }
        final JsonNode node = root.at(jsonPointer);
        if (node.isMissingNode() || node.asText("").isEmpty()) {
            throw new IllegalArgumentException("Missing required json node field: " + jsonPointer);
        }
        return node.asText("");
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("jsonPointer", jsonPointer)
                          .toString();
    }
}
