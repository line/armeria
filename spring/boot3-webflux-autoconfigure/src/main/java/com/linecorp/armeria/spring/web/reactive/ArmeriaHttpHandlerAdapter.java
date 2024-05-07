/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.HttpHandler;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.spring.internal.common.DataBufferFactoryWrapper;

import reactor.core.publisher.Mono;

/**
 * An adapter which adapts the {@link HttpHandler} to the Armeria server.
 */
final class ArmeriaHttpHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaHttpHandlerAdapter.class);

    private final HttpHandler httpHandler;
    private final DataBufferFactoryWrapper<?> factoryWrapper;

    ArmeriaHttpHandlerAdapter(HttpHandler httpHandler, DataBufferFactoryWrapper<?> factoryWrapper) {
        this.httpHandler = requireNonNull(httpHandler, "httpHandler");
        this.factoryWrapper = requireNonNull(factoryWrapper, "factoryWrapper");
    }

    Mono<Void> handle(ServiceRequestContext ctx, HttpRequest req, CompletableFuture<HttpResponse> future,
                      @Nullable String serverHeader) {
        final ArmeriaServerHttpRequest convertedRequest;
        try {
            convertedRequest = new ArmeriaServerHttpRequest(ctx, req, factoryWrapper);
        } catch (Exception e) {
            final String path = req.path();
            logger.warn("{} Invalid request path: {}", ctx, path, e);
            future.complete(HttpResponse.of(HttpStatus.BAD_REQUEST,
                                            MediaType.PLAIN_TEXT_UTF_8,
                                            HttpStatus.BAD_REQUEST + "\nInvalid request path: " + path));
            return Mono.empty();
        }

        final ArmeriaServerHttpResponse convertedResponse =
                new ArmeriaServerHttpResponse(ctx, future, factoryWrapper, serverHeader);
        return httpHandler.handle(convertedRequest, convertedResponse)
                          .doOnSuccess(unused -> {
                              convertedResponse.setComplete().subscribe();
                          })
                          .doOnError(cause -> {
                              logger.debug("{} Failed to handle a request", ctx, cause);
                              convertedResponse.setComplete(cause).subscribe();
                          });
    }
}
