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

package com.linecorp.armeria.server.logging;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.internal.common.logging.LoggingDecorators.logRequest;
import static com.linecorp.armeria.internal.common.logging.LoggingDecorators.logResponse;
import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * Decorates an {@link HttpService} to log {@link HttpRequest}s and {@link HttpResponse}s.
 */
public final class LoggingService extends SimpleDecoratingHttpService {

    private static final Logger defaultLogger = LoggerFactory.getLogger(LoggingService.class);

    /**
     * Returns a new {@link HttpService} decorator that logs {@link HttpRequest}s and {@link HttpResponse}s at
     * {@link LogLevel#INFO} for success, {@link LogLevel#WARN} for failure.
     *
     * @see LoggingServiceBuilder for more information on the default settings.
     */
    public static Function<? super HttpService, LoggingService> newDecorator() {
        return builder().requestLogLevel(LogLevel.INFO)
                        .successfulResponseLogLevel(LogLevel.INFO)
                        .failureResponseLogLevel(LogLevel.WARN)
                        .newDecorator();
    }

    /**
     * Returns a newly created {@link LoggingServiceBuilder}.
     */
    public static LoggingServiceBuilder builder() {
        return new LoggingServiceBuilder();
    }

    private final RequestLogger requestLogger = new RequestLogger();
    private final ResponseLogger responseLogger = new ResponseLogger();

    private final Logger logger;
    private final Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper;
    private final Function<? super RequestLog, LogLevel> responseLogLevelMapper;
    private final Function<? super RequestHeaders, ?> requestHeadersSanitizer;
    private final Function<Object, ?> requestContentSanitizer;
    private final Function<? super HttpHeaders, ?> requestTrailersSanitizer;

    private final Function<? super ResponseHeaders, ?> responseHeadersSanitizer;
    private final Function<Object, ?> responseContentSanitizer;
    private final Function<? super HttpHeaders, ?> responseTrailersSanitizer;
    private final Function<? super Throwable, ?> responseCauseSanitizer;
    private final Sampler<? super ServiceRequestContext> sampler;

    /**
     * Creates a new instance that logs {@link HttpRequest}s and {@link HttpResponse}s at the specified
     * {@link LogLevel}s with the specified sanitizers.
     */
    LoggingService(
            HttpService delegate,
            @Nullable Logger logger,
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper,
            Function<? super RequestLog, LogLevel> responseLogLevelMapper,
            Function<? super RequestHeaders, ?> requestHeadersSanitizer,
            Function<Object, ?> requestContentSanitizer,
            Function<? super HttpHeaders, ?> requestTrailersSanitizer,
            Function<? super ResponseHeaders, ?> responseHeadersSanitizer,
            Function<Object, ?> responseContentSanitizer,
            Function<? super HttpHeaders, ?> responseTrailersSanitizer,
            Function<? super Throwable, ?> responseCauseSanitizer,
            Sampler<? super ServiceRequestContext> sampler) {

        super(requireNonNull(delegate, "delegate"));

        this.logger = firstNonNull(logger, defaultLogger);
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
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (sampler.isSampled(ctx)) {
            ctx.log().whenRequestComplete().thenAccept(requestLogger);
            ctx.log().whenComplete().thenAccept(responseLogger);
        }
        return delegate().serve(ctx, req);
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
                        requestTrailersSanitizer,
                        responseHeadersSanitizer,
                        responseContentSanitizer,
                        responseTrailersSanitizer,
                        responseCauseSanitizer);
        }
    }
}
