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

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.HttpHandler;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

import reactor.core.publisher.Mono;

/**
 * An adapter which adapts the {@link HttpHandler} to the Armeria server.
 */
final class ArmeriaHttpHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaHttpHandlerAdapter.class);

    private final HttpHandler httpHandler;
    private final ArmeriaBufferFactory factory;

    ArmeriaHttpHandlerAdapter(HttpHandler httpHandler, ArmeriaBufferFactory factory) {
        this.httpHandler = requireNonNull(httpHandler, "httpHandler");
        this.factory = requireNonNull(factory, "factory");
    }

    Mono<Void> handle(ServiceRequestContext ctx, HttpRequest req, CompletableFuture<HttpResponse> future,
                      @Nullable String serverHeader) {
        final ArmeriaServerHttpRequest convertedRequest;
        try {
            convertedRequest = new ArmeriaServerHttpRequest(ctx, req, factory);
        } catch (Exception e) {
            logger.warn("{} Invalid request path: {}", ctx, req.path(), e);
            future.complete(HttpResponse.of(HttpStatus.BAD_REQUEST));
            return Mono.empty();
        }

        final ArmeriaServerHttpResponse convertedResponse =
                new ArmeriaServerHttpResponse(ctx, future, factory, serverHeader);
        return httpHandler.handle(convertedRequest, convertedResponse)
                          .doOnSuccessOrError((unused, cause) -> {
                              if (cause != null) {
                                  logger.debug("{} Failed to handle a request", ctx, cause);
                                  convertedResponse.setComplete(cause).subscribe();
                              } else {
                                  convertedResponse.setComplete().subscribe();
                              }
                          });
    }
}
