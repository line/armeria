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

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogLevelMapper;
import com.linecorp.armeria.common.logging.ResponseLogLevelMapper;
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
    private final RequestLogLevelMapper requestLogLevelMapper;
    private final ResponseLogLevelMapper responseLogLevelMapper;
    private final Predicate<Throwable> responseCauseFilter;
    private final Sampler<? super RequestLog> sampler;
    private final LogFormatter logFormatter;

    /**
     * Creates a new instance that logs {@link HttpRequest}s and {@link HttpResponse}s at the specified
     * {@link LogLevel}s with the specified sanitizers.
     */
    LoggingService(
            HttpService delegate,
            @Nullable Logger logger,
            RequestLogLevelMapper requestLogLevelMapper,
            ResponseLogLevelMapper responseLogLevelMapper,
            Predicate<Throwable> responseCauseFilter,
            Sampler<? super ServiceRequestContext> successSampler,
            Sampler<? super ServiceRequestContext> failureSampler,
            LogFormatter logFormatter) {

        super(requireNonNull(delegate, "delegate"));

        this.logger = firstNonNull(logger, defaultLogger);
        this.requestLogLevelMapper = requireNonNull(requestLogLevelMapper, "requestLogLevelMapper");
        this.responseLogLevelMapper = requireNonNull(responseLogLevelMapper, "responseLogLevelMapper");
        this.responseCauseFilter = requireNonNull(responseCauseFilter, "responseCauseFilter");
        this.logFormatter = requireNonNull(logFormatter, "logFormatter");
        requireNonNull(successSampler, "successSampler");
        requireNonNull(failureSampler, "failureSampler");
        sampler = requestLog -> {
            final ServiceRequestContext ctx = (ServiceRequestContext) requestLog.context();
            if (ctx.config().successFunction().isSuccess(ctx, requestLog)) {
                return successSampler.isSampled(ctx);
            }
            return failureSampler.isSampled(ctx);
        };
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        ctx.log().whenComplete().thenAccept(requestLog -> {
            if (sampler.isSampled(requestLog)) {
                log(logger, ctx, requestLog, requestLogger, responseLogger);
            }
        });
        return unwrap().serve(ctx, req);
    }

    private class RequestLogger implements Consumer<RequestLog> {
        @Override
        public void accept(RequestLog log) {
            logRequest(logger, log,
                       requestLogLevelMapper,
                       logFormatter);
        }
    }

    private class ResponseLogger implements Consumer<RequestLog> {
        @Override
        public void accept(RequestLog log) {
            logResponse(logger, log,
                        requestLogLevelMapper,
                        responseLogLevelMapper,
                        responseCauseFilter,
                        logFormatter);
        }
    }
}
