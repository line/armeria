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
import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.Functions;
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

    private final BiFunction<? super RequestContext, ? super HttpHeaders, ?> requestHeadersSanitizer;
    private final BiFunction<? super RequestContext, Object, ?> requestContentSanitizer;
    private final BiFunction<? super RequestContext, ? super HttpHeaders, ?> requestTrailersSanitizer;

    private final BiFunction<? super RequestContext, ? super HttpHeaders, ?> responseHeadersSanitizer;
    private final BiFunction<? super RequestContext, Object, ?> responseContentSanitizer;
    private final BiFunction<? super RequestContext, ? super HttpHeaders, ?> responseTrailersSanitizer;
    private final BiFunction<? super RequestContext, ? super Throwable, ?> responseCauseSanitizer;

    private final Sampler<? super ClientRequestContext> sampler;

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}.
     */
    AbstractLoggingClient(Client<I, O> delegate, LogLevel level) {
        this(delegate,
             null,
             log -> level,
             log -> level,
             Functions.second(),
             Functions.second(),
             Functions.second(),
             Functions.second(),
             Functions.second(),
             Functions.second(),
             Functions.second(),
             Sampler.always());
    }

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}s with the specified sanitizers.
     */
    AbstractLoggingClient(
            Client<I, O> delegate,
            @Nullable Logger logger,
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper,
            Function<? super RequestLog, LogLevel> responseLogLevelMapper,
            BiFunction<? super RequestContext, ? super HttpHeaders, ?> requestHeadersSanitizer,
            BiFunction<? super RequestContext, Object, ?> requestContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ?> requestTrailersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ?> responseHeadersSanitizer,
            BiFunction<? super RequestContext, Object, ?> responseContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ?> responseTrailersSanitizer,
            BiFunction<? super RequestContext, ? super Throwable, ?> responseCauseSanitizer,
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
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        if (sampler.isSampled(ctx)) {
            ctx.log().whenRequestComplete().thenAccept(requestLogger);
            ctx.log().whenComplete().thenAccept(responseLogger);
        }
        return delegate().execute(ctx, req);
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
