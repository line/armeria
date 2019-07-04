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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;

final class DefaultHealthCheckUpdateHandler implements HealthCheckUpdateHandler {

    static final DefaultHealthCheckUpdateHandler INSTANCE = new DefaultHealthCheckUpdateHandler();

    private final ObjectMapper mapper = new ObjectMapper();

    private DefaultHealthCheckUpdateHandler() {}

    @Override
    public CompletionStage<Boolean> handle(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        requireNonNull(req, "req");
        switch (req.method()) {
            case PUT:
            case POST:
                return req.aggregate().thenApply(this::handlePut);
            case PATCH:
                return req.aggregate().thenApply(this::handlePatch);
            default:
                throw HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED);
        }
    }

    private Boolean handlePut(AggregatedHttpRequest req) {
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

        return healthy.booleanValue();
    }

    private Boolean handlePatch(AggregatedHttpRequest req) {
        final String json;
        try {
            json = mapper.writeValueAsString(toJsonNode(req));
        } catch (JsonProcessingException e) {
            throw new Error(e); // Never happens.
        }

        switch (json) {
            case "[{\"op\":\"replace\",\"path\":\"/healthy\",\"value\":true}]":
                return true;
            case "[{\"op\":\"replace\",\"path\":\"/healthy\",\"value\":false}]":
                return false;
            default:
                throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }
    }

    private JsonNode toJsonNode(AggregatedHttpRequest req) {
        final MediaType contentType = req.contentType();
        if (contentType != null && !contentType.is(MediaType.JSON)) {
            throw HttpStatusException.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        final Charset charset = contentType == null ? StandardCharsets.UTF_8
                                                    : contentType.charset().orElse(StandardCharsets.UTF_8);
        try {
            return mapper.readTree(req.content(charset));
        } catch (IOException e) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }
    }
}
