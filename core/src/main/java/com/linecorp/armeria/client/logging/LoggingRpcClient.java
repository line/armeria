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

import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.Sampler;

/**
 * Decorates an {@link RpcClient} to log {@link Request}s and {@link Response}s.
 */
public final class LoggingRpcClient extends AbstractLoggingClient<RpcRequest, RpcResponse>
        implements RpcClient {

    /**
     * Returns a new {@link RpcClient} decorator that logs {@link Request}s and {@link Response}s at
     * {@link LogLevel#DEBUG} for success, {@link LogLevel#WARN} for failure.
     * See {@link LoggingRpcClientBuilder} for more information on the default settings.
     */
    public static Function<? super RpcClient, LoggingRpcClient> newDecorator() {
        return builder().newDecorator();
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
     * If the logger is null, it means that the default logger is used.
     */
    LoggingRpcClient(
            RpcClient delegate,
            @Nullable Logger logger,
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper,
            Function<? super RequestLog, LogLevel> responseLogLevelMapper,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> requestContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseHeadersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> responseContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseTrailersSanitizer,
            BiFunction<? super RequestContext, ? super Throwable,
                    ? extends @Nullable Object> responseCauseSanitizer,
            Sampler<? super ClientRequestContext> sampler) {

        super(delegate, logger, requestLogLevelMapper, responseLogLevelMapper,
              requestHeadersSanitizer, requestContentSanitizer, requestTrailersSanitizer,
              responseHeadersSanitizer, responseContentSanitizer, responseTrailersSanitizer,
              responseCauseSanitizer, sampler);
    }
}
