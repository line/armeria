/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * A function that determines whether a {@link Client} and {@link Service} handled a request successfully
 * or not.
 * This function can be used by the decorators like the following to determine
 * whether the request was handled successfully or not:
 * <ul>
 *   <li>{@link MetricCollectingClient}</li>
 *   <li>{@link MetricCollectingService}</li>
 *   <li>{@link LoggingClient}</li>
 *   <li>{@link LoggingService}</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * ServerBuilder sb = Server
 *         .builder()
 *         .successFunction((ctx, req) -> req.responseHeaders().status().code() == 200 ||
 *                                        req.responseHeaders().status().code() == 404)
 *         .decorator(MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("myServer")))
 *         .decorator(LoggingService.newDecorator()));
 *
 * WebClient client = WebClient
 *         .builder(uri)
 *         .successFunction((ctx, req) -> req.responseHeaders().status().code() == 200 ||
 *                                        req.responseHeaders().status().code() == 404)
 *         .decorator(MetricCollectingClient.newDecorator(MeterIdPrefixFunction.ofDefault("myClient")))
 *         .decorator(LoggingClient.newDecorator()))
 *         .build();
 * }
 * </pre>
 *
 */
@FunctionalInterface
@UnstableApi
public interface SuccessFunction {
    /**
     * Returns a {@link SuccessFunction} that will always return {@code false}.
     */
    static SuccessFunction never() {
        return (ctx, log) -> false;
    }

    /**
     * Returns a {@link SuccessFunction} that will always return {@code true}.
     */
    static SuccessFunction always() {
        return (ctx, log) -> true;
    }

    /**
     * Returns the default success classification function which checks
     * {@link RequestLog#responseCause()} is null, 100 &lt;= {@link HttpStatus} &lt; 400
     * and {@link RpcResponse#isCompletedExceptionally()} == {@code false}.
     */
    static SuccessFunction ofDefault() {
        return (ctx, log) -> {
            if (log.responseCause() != null) {
                return false;
            }

            final int statusCode = log.responseHeaders().status().code();
            if (statusCode < 100 || statusCode >= 400) {
                return false;
            }

            final Object responseContent = log.responseContent();
            if (responseContent instanceof RpcResponse) {
                return !((RpcResponse) responseContent).isCompletedExceptionally();
            }

            return true;
        };
    }

    /**
     * Returns {@code true} if the request was handled successfully.
     *
     * @see ServerBuilder#successFunction(SuccessFunction)
     * @see ClientBuilder#successFunction(SuccessFunction)
     */
    boolean isSuccess(RequestContext ctx, RequestLog log);
}
