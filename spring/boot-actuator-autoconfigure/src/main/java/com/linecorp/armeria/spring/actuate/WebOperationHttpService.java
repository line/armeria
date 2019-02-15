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

package com.linecorp.armeria.spring.actuate;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebOperation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * {@link HttpService} to handle a {@link WebOperation}. Mostly inspired by reactive implementation in
 * {@link org.springframework.boot.actuate.endpoint.web.reactive.AbstractWebFluxEndpointHandlerMapping}.
 */
final class WebOperationHttpService implements HttpService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private final WebOperation operation;

    WebOperationHttpService(WebOperation operation) {
        this.operation = operation;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        final CompletableFuture<HttpResponse> resFuture = new CompletableFuture<>();
        req.aggregate().handle((msg, t) -> {
            if (t != null) {
                resFuture.completeExceptionally(t);
                return null;
            }
            if (operation.isBlocking()) {
                ctx.blockingTaskExecutor().execute(() -> invoke(ctx, msg, resFuture));
            } else {
                invoke(ctx, msg, resFuture);
            }
            return null;
        });
        return HttpResponse.from(resFuture);
    }

    private void invoke(ServiceRequestContext ctx,
                        AggregatedHttpMessage msg,
                        CompletableFuture<HttpResponse> resFuture) {
        final Map<String, Object> arguments = getArguments(ctx, msg);
        final Object result = operation.invoke(new InvocationContext(SecurityContext.NONE, arguments));

        try {
            final HttpResponse res = handleResult(result, msg.method());
            resFuture.complete(res);
        } catch (IOException e) {
            resFuture.completeExceptionally(e);
        }
    }

    private static HttpResponse handleResult(@Nullable Object result, HttpMethod method) throws IOException {
        if (result == null) {
            return HttpResponse.of(method != HttpMethod.GET ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND);
        }

        if (!(result instanceof WebEndpointResponse)) {
            return HttpResponse.of(HttpStatus.OK,
                                   MediaType.JSON_UTF_8,
                                   OBJECT_MAPPER.writeValueAsBytes(result));
        }

        final WebEndpointResponse<?> response = (WebEndpointResponse<?>) result;
        return HttpResponse.of(
                HttpStatus.valueOf(response.getStatus()),
                MediaType.JSON_UTF_8,
                OBJECT_MAPPER.writeValueAsBytes(response.getBody())
        );
    }

    private static Map<String, Object> getArguments(ServiceRequestContext ctx, AggregatedHttpMessage msg) {
        final Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.putAll(ctx.pathParams());
        if (!msg.content().isEmpty()) {
            final Map<String, Object> bodyParams;
            try {
                bodyParams = OBJECT_MAPPER.readValue(msg.content().array(), JSON_MAP);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid JSON in request.");
            }
            arguments.putAll(bodyParams);
        }
        final String query = ctx.query();
        if (query != null) {
            QueryStringDecoder queryStringDecoder = new QueryStringDecoder(query, false);
            queryStringDecoder.parameters().forEach(
                    (key, values) -> arguments.put(key, values.size() != 1 ? values : values.get(0)));
        }
        return ImmutableMap.copyOf(arguments);
    }
}
