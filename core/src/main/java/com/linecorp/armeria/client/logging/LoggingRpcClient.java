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

package com.linecorp.armeria.client.logging;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.Sampler;

/**
 * Decorates an {@link RpcClient} to log {@link Request}s and {@link Response}s.
 */
public final class LoggingRpcClient extends AbstractLoggingClient<RpcRequest, RpcResponse>
        implements RpcClient {

    /**
     * Returns a new {@link RpcClient} decorator that logs {@link Request}s and {@link Response}s at
     * {@link LogLevel#INFO} for success, {@link LogLevel#WARN} for failure.
     *
     * @see LoggingRpcClientBuilder for more information on the default settings.
     */
    public static Function<? super RpcClient, LoggingRpcClient> newDecorator() {
        return builder().requestLogLevel(LogLevel.INFO)
                        .successfulResponseLogLevel(LogLevel.INFO)
                        .failureResponseLogLevel(LogLevel.WARN)
                        .newDecorator();
    }

    /**
     * Returns a newly created {@link LoggingRpcClientBuilder}.
     */
    public static LoggingRpcClientBuilder builder() {
        return new LoggingRpcClientBuilder();
    }

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}s with the specified sanitizers.
     */
    LoggingRpcClient(RpcClient delegate,
                     Function<? super RequestLog, LogLevel> requestLogLevelMapper,
                     Function<? super RequestLog, LogLevel> responseLogLevelMapper,
                     Function<? super HttpHeaders, ?> requestHeadersSanitizer,
                     Function<Object, ?> requestContentSanitizer,
                     Function<? super HttpHeaders, ?> requestTrailersSanitizer,
                     Function<? super HttpHeaders, ?> responseHeadersSanitizer,
                     Function<Object, ?> responseContentSanitizer,
                     Function<? super HttpHeaders, ?> responseTrailersSanitizer,
                     Function<? super Throwable, ?> responseCauseSanitizer,
                     Sampler<? super ClientRequestContext> sampler) {
        super(delegate, requestLogLevelMapper, responseLogLevelMapper,
              requestHeadersSanitizer, requestContentSanitizer, requestTrailersSanitizer,
              responseHeadersSanitizer, responseContentSanitizer, responseTrailersSanitizer,
              responseCauseSanitizer, sampler);
    }
}
