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

import static com.linecorp.armeria.internal.common.logging.LoggingDecorators.logRequest;
import static com.linecorp.armeria.internal.common.logging.LoggingDecorators.logResponse;
import static com.linecorp.armeria.internal.common.logging.LoggingDecorators.logWhenComplete;
import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.Sampler;

/**
 * Decorates a {@link Client} to log {@link Request}s and {@link Response}s.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
abstract class AbstractLoggingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private final RequestLogger requestLogger = new RequestLogger();
    private final ResponseLogger responseLogger = new ResponseLogger();

    private final Logger logger;
    private final Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper;
    private final Function<? super RequestLog, LogLevel> responseLogLevelMapper;

    private final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
            requestHeadersSanitizer;
    private final BiFunction<? super RequestContext, Object, ? extends @Nullable Object>
            requestContentSanitizer;
    private final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
            requestTrailersSanitizer;

    private final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
            responseHeadersSanitizer;
    private final BiFunction<? super RequestContext, Object, ? extends @Nullable Object>
            responseContentSanitizer;
    private final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
            responseTrailersSanitizer;
    private final BiFunction<? super RequestContext, ? super Throwable, ? extends @Nullable Object>
            responseCauseSanitizer;

    private final Sampler<? super ClientRequestContext> sampler;

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}s with the specified sanitizers.
     */
    AbstractLoggingClient(
            Client<I, O> delegate,
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

        super(requireNonNull(delegate, "delegate"));

        this.logger = logger != null ? logger : LoggerFactory.getLogger(getClass());
        this.requestLogLevelMapper = requireNonNull(requestLogLevelMapper, "requestLogLevelMapper");
        this.responseLogLevelMapper = requireNonNull(responseLogLevelMapper, "responseLogLevelMapper");

        this.requestHeadersSanitizer = requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        this.requestTrailersSanitizer = requireNonNull(requestTrailersSanitizer, "requestTrailersSanitizer");

        this.responseHeadersSanitizer = requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "responseContentSanitizer");
        this.responseTrailersSanitizer = requireNonNull(responseTrailersSanitizer, "responseTrailersSanitizer");
        this.responseCauseSanitizer = requireNonNull(responseCauseSanitizer, "responseCauseSanitizer");
        this.sampler = requireNonNull(sampler, "sampler");
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        if (sampler.isSampled(ctx)) {
            logWhenComplete(logger, ctx, requestLogger, responseLogger);
        }
        return unwrap().execute(ctx, req);
    }

    private class RequestLogger implements Consumer<RequestOnlyLog> {
        @Override
        public void accept(RequestOnlyLog log) {
            logRequest(logger, log,
                       requestLogLevelMapper,
                       requestHeadersSanitizer,
                       requestContentSanitizer, requestTrailersSanitizer);
        }
    }

    private class ResponseLogger implements Consumer<RequestLog> {
        @Override
        public void accept(RequestLog log) {
            logResponse(logger, log,
                        requestLogLevelMapper,
                        responseLogLevelMapper,
                        requestHeadersSanitizer,
                        requestContentSanitizer,
                        requestHeadersSanitizer,
                        responseHeadersSanitizer,
                        responseContentSanitizer,
                        responseTrailersSanitizer,
                        responseCauseSanitizer);
        }
    }
}
