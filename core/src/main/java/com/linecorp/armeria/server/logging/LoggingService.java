/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * Decorates a {@link Service} to log {@link Request}s and {@link Response}s.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
// TODO(anuraag): Make final after removing SampledLoggingService, there isn't a good reason to make this
// extensible.
public class LoggingService<I extends Request, O extends Response> extends SimpleDecoratingService<I, O> {

    @VisibleForTesting
    static final String REQUEST_FORMAT = "Request: {}";
    @VisibleForTesting
    static final String RESPONSE_FORMAT = "Response: {}";

    /**
     * Returns a new {@link Service} decorator that logs {@link Request}s and {@link Response}s at
     * {@link LogLevel#INFO} for success, {@link LogLevel#WARN} for failure.
     *
     * @see LoggingServiceBuilder for more information on the default settings.
     */
    public static <I extends Request, O extends Response>
    Function<Service<I, O>, LoggingService<I, O>> newDecorator() {
        return new LoggingServiceBuilder()
                .requestLogLevel(LogLevel.INFO)
                .successfulResponseLogLevel(LogLevel.INFO)
                .failureResponseLogLevel(LogLevel.WARN)
                .newDecorator();
    }

    /**
     * @deprecated Use {@link LoggingServiceBuilder}.
     */
    @Deprecated
    public static <I extends Request, O extends Response>
    Function<Service<I, O>, LoggingService<I, O>> newDecorator(LogLevel level) {
        return delegate -> new LoggingService<>(delegate, level);
    }

    private final LogLevel requestLogLevel;
    private final LogLevel successfulResponseLogLevel;
    private final LogLevel failedResponseLogLevel;
    private final Function<HttpHeaders, HttpHeaders> requestHeadersSanitizer;
    private final Function<Object, Object> requestContentSanitizer;
    private final Function<HttpHeaders, HttpHeaders> responseHeadersSanitizer;
    private final Function<Object, Object> responseContentSanitizer;
    private final Sampler sampler;

    /**
     * @deprecated Use {@link LoggingService#newDecorator()}.
     */
    @Deprecated
    public LoggingService(Service<I, O> delegate) {
        this(delegate, LogLevel.INFO);
    }

    /**
     * @deprecated Use {@link LoggingServiceBuilder}.
     */
    @Deprecated
    public LoggingService(Service<I, O> delegate, LogLevel level) {
        this(delegate,
             level,
             level,
             level,
             Function.identity(),
             Function.identity(),
             Function.identity(),
             Function.identity(),
             Sampler.ALWAYS_SAMPLE);
    }

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}s with the specified sanitizers.
     */
    LoggingService(
            Service<I, O> delegate,
            LogLevel requestLogLevel,
            LogLevel successfulResponseLogLevel,
            LogLevel failedResponseLogLevel,
            Function<HttpHeaders, HttpHeaders> requestHeadersSanitizer,
            Function<Object, Object> requestContentSanitizer,
            Function<HttpHeaders, HttpHeaders> responseHeadersSanitizer,
            Function<Object, Object> responseContentSanitizer,
            Sampler sampler) {
        super(requireNonNull(delegate, "delegate"));
        this.requestLogLevel = requireNonNull(requestLogLevel, "requestLogLevel");
        this.successfulResponseLogLevel =
                requireNonNull(successfulResponseLogLevel, "successfulResponseLogLevel");
        this.failedResponseLogLevel = requireNonNull(failedResponseLogLevel, "failedResponseLogLevel");
        this.requestHeadersSanitizer = requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        this.responseHeadersSanitizer = requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "resposneContentSanitizer");
        this.sampler = requireNonNull(sampler, "sampler");
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        if (sampler.isSampled()) {
            ctx.log().addListener(this::logRequest, RequestLogAvailability.REQUEST_END);
            ctx.log().addListener(this::logResponse, RequestLogAvailability.COMPLETE);
        }
        return delegate().serve(ctx, req);
    }

    /**
     * Logs a stringified request of {@link RequestLog}.
     */
    // TODO(anuraag): Make private after removing SampledLoggingService, there isn't a good reason to make this
    // extensible.
    protected void logRequest(RequestLog log) {
        final Logger logger = ((ServiceRequestContext) log.context()).logger();
        if (requestLogLevel.isEnabled(logger)) {
            requestLogLevel.log(logger, REQUEST_FORMAT,
                                log.toStringRequestOnly(requestHeadersSanitizer, requestContentSanitizer));
        }
    }

    /**
     * Logs a stringified response of {@link RequestLog}.
     */
    // TODO(anuraag): Make private after removing SampledLoggingService, there isn't a good reason to make this
    // extensible.
    protected void logResponse(RequestLog log) {
        final Logger logger = ((ServiceRequestContext) log.context()).logger();
        final LogLevel level =
                log.responseCause() == null ? successfulResponseLogLevel : failedResponseLogLevel;
        if (level.isEnabled(logger)) {
            level.log(logger, RESPONSE_FORMAT,
                      log.toStringResponseOnly(responseHeadersSanitizer, responseContentSanitizer));
        }
    }
}
