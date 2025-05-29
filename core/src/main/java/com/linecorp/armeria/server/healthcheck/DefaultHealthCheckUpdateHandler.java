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
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Handler which updates the healthiness of the {@link Server}. Supports {@code PUT}, {@code POST} and
 * {@code PATCH} requests and tells if the {@link Server} needs to be marked as healthy or unhealthy.
 */
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
                return UnmodifiableFuture.exceptionallyCompletedFuture(
                        HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED));
        }
    }

    private static HealthCheckUpdateResult handlePut(AggregatedHttpRequest req) {
        final JsonNode json = toJsonNode(req);
        if (json.getNodeType() != JsonNodeType.OBJECT) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }

        if (json.has("healthy")) {
            final JsonNode jsonNode = json.get("healthy");
            if (jsonNode == null) {
                throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
            }
            if (jsonNode.getNodeType() != JsonNodeType.BOOLEAN) {
                throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
            }

            return jsonNode.booleanValue() ? HealthCheckUpdateResult.HEALTHY
                                           : HealthCheckUpdateResult.UNHEALTHY;
        } else if (json.has("status")) {
            final JsonNode status = json.get("status");
            if (status == null) {
                throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
            }
            if (status.getNodeType() != JsonNodeType.STRING) {
                throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
            }

            return getHealthCheckUpdateResult(status);
        } else {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }
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
            !"replace".equals(op.textValue())) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }

        if ("/healthy".equals(path.textValue()) && value.isBoolean()) {
            return value.isBoolean() ? value.booleanValue() ? HealthCheckUpdateResult.HEALTHY
                                                            : HealthCheckUpdateResult.UNHEALTHY
                                     : HealthCheckUpdateResult.UNHEALTHY;
        }

        if ("/status".equals(path.textValue()) && value.isTextual()) {
            return getHealthCheckUpdateResult(value);
        }

        throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
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

    private static HealthCheckUpdateResult getHealthCheckUpdateResult(JsonNode status) {
        switch (status.textValue()) {
            case "HEALTHY":
                return HealthCheckUpdateResult.HEALTHY;
            case "DEGRADED":
                return HealthCheckUpdateResult.DEGRADED;
            case "STOPPING":
                return HealthCheckUpdateResult.STOPPING;
            case "UNHEALTHY":
                return HealthCheckUpdateResult.UNHEALTHY;
            case "UNDER_MAINTENANCE":
                return HealthCheckUpdateResult.UNDER_MAINTENANCE;
            default:
                throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }
    }
}
