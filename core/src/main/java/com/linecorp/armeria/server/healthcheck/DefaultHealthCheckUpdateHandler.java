/*
 * Copyright 2019 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.server.healthcheck;

import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;

enum DefaultHealthCheckUpdateHandler implements HealthCheckUpdateHandler {

    INSTANCE;

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public CompletionStage<HealthCheckUpdateResult> handle(ServiceRequestContext ctx,
                                                           HttpRequest req) throws Exception {
        requireNonNull(req, "req");
        switch (req.method()) {
            case PUT:
            case POST:
                return req.aggregate().thenApply(DefaultHealthCheckUpdateHandler::handlePut);
            case PATCH:
                return req.aggregate().thenApply(DefaultHealthCheckUpdateHandler::handlePatch);
            default:
                return exceptionallyCompletedFuture(HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED));
        }
    }

    private static HealthCheckUpdateResult handlePut(AggregatedHttpRequest req) {
        final JsonNode json = toJsonNode(req);
        if (json.getNodeType() != JsonNodeType.OBJECT) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }

        final JsonNode healthy = json.get("healthy");
        if (healthy == null) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }
        if (healthy.getNodeType() != JsonNodeType.BOOLEAN) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }

        return healthy.booleanValue() ? HealthCheckUpdateResult.HEALTHY
                                      : HealthCheckUpdateResult.UNHEALTHY;
    }

    private static HealthCheckUpdateResult handlePatch(AggregatedHttpRequest req) {
        final JsonNode json = toJsonNode(req);
        if (json.getNodeType() != JsonNodeType.ARRAY ||
            json.size() != 1) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }

        final JsonNode patchCommand = json.get(0);
        final JsonNode op = patchCommand.get("op");
        final JsonNode path = patchCommand.get("path");
        final JsonNode value = patchCommand.get("value");
        if (op == null || path == null || value == null ||
            !"replace".equals(op.textValue()) ||
            !"/healthy".equals(path.textValue()) ||
            !value.isBoolean()) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }

        return value.booleanValue() ? HealthCheckUpdateResult.HEALTHY
                                    : HealthCheckUpdateResult.UNHEALTHY;
    }

    private static JsonNode toJsonNode(AggregatedHttpRequest req) {
        final MediaType contentType = req.contentType();
        if (contentType != null && !contentType.is(MediaType.JSON)) {
            throw HttpStatusException.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        final Charset charset = contentType == null ? StandardCharsets.UTF_8
                                                    : contentType.charset(StandardCharsets.UTF_8);
        try {
            return StandardCharsets.UTF_8.equals(charset) ? mapper.readTree(req.content().array())
                                                          : mapper.readTree(req.content(charset));
        } catch (IOException e) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }
    }
}
