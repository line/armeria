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
import static com.linecorp.armeria.internal.common.logging.LoggingDecorators.log;
import static com.linecorp.armeria.internal.common.logging.LoggingDecorators.logRequest;
import static com.linecorp.armeria.internal.common.logging.LoggingDecorators.logResponse;
import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
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
     * {@link LogLevel#DEBUG} for success, {@link LogLevel#WARN} for failure. See {@link LoggingServiceBuilder}
     * for more information on the default settings.
     */
    public static Function<? super HttpService, LoggingService> newDecorator() {
        return builder().newDecorator();
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

    private final BiFunction<? super RequestContext, ? super RequestHeaders,
            ? extends @Nullable Object> requestHeadersSanitizer;
    private final BiFunction<? super RequestContext, Object,
            ? extends @Nullable Object> requestContentSanitizer;
    private final BiFunction<? super RequestContext, ? super HttpHeaders,
            ? extends @Nullable Object> requestTrailersSanitizer;

    private final BiFunction<? super RequestContext, ? super ResponseHeaders,
            ? extends @Nullable Object> responseHeadersSanitizer;
    private final BiFunction<? super RequestContext, Object,
            ? extends @Nullable Object> responseContentSanitizer;
    private final BiFunction<? super RequestContext, ? super HttpHeaders,
            ? extends @Nullable Object> responseTrailersSanitizer;
    private final BiFunction<? super RequestContext, ? super Throwable,
            ? extends @Nullable Object> responseCauseSanitizer;

    private final Sampler<? super ServiceRequestContext> successSampler;
    private final Sampler<? super ServiceRequestContext> failureSampler;

    /**
     * Creates a new instance that logs {@link HttpRequest}s and {@link HttpResponse}s at the specified
     * {@link LogLevel}s with the specified sanitizers.
     */
    LoggingService(
            HttpService delegate,
            @Nullable Logger logger,
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper,
            Function<? super RequestLog, LogLevel> responseLogLevelMapper,
            BiFunction<? super RequestContext, ? super RequestHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> requestContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer,
            BiFunction<? super RequestContext, ? super ResponseHeaders,
                    ? extends @Nullable Object> responseHeadersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> responseContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseTrailersSanitizer,
            BiFunction<? super RequestContext, ? super Throwable,
                    ? extends @Nullable Object> responseCauseSanitizer,
            Sampler<? super ServiceRequestContext> successSampler,
            Sampler<? super ServiceRequestContext> failureSampler) {

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
        this.successSampler = requireNonNull(successSampler, "successSampler");
        this.failureSampler = requireNonNull(failureSampler, "failureSampler");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        ctx.log().whenComplete().thenAccept(requestLog -> {
            if (requestLog.responseCause() == null) {
                if (successSampler.isSampled(ctx)) {
                    log(logger, ctx, requestLog, requestLogger, responseLogger);
                }
            } else if (failureSampler.isSampled(ctx)) {
                log(logger, ctx, requestLog, requestLogger, responseLogger);
            }
        });
        return unwrap().serve(ctx, req);
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
