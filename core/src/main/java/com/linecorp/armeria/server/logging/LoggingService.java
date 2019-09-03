/*
 * Copyright 2016 LINE Corporation
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

import static com.linecorp.armeria.internal.logging.LoggingDecorators.logRequest;
import static com.linecorp.armeria.internal.logging.LoggingDecorators.logResponse;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * Decorates a {@link Service} to log {@link Request}s and {@link Response}s.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public final class LoggingService<I extends Request, O extends Response> extends SimpleDecoratingService<I, O> {
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
     * Returns a new {@link Service} decorator that logs {@link Request}s and {@link Response}s at the given
     * {@link LogLevel}.
     *
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
    private final Function<? super RequestHeaders, ?> requestHeadersSanitizer;
    private final Function<Object, ?> requestContentSanitizer;
    private final Function<? super HttpHeaders, ?> requestTrailersSanitizer;

    private final Function<? super ResponseHeaders, ?> responseHeadersSanitizer;
    private final Function<Object, ?> responseContentSanitizer;
    private final Function<? super HttpHeaders, ?> responseTrailersSanitizer;
    private final Function<? super Throwable, ?> responseCauseSanitizer;
    private final Sampler<? super ServiceRequestContext> sampler;

    /**
     * Creates a new instance.
     *
     * @deprecated Use {@link LoggingService#newDecorator()}.
     */
    @Deprecated
    public LoggingService(Service<I, O> delegate) {
        this(delegate, LogLevel.INFO);
    }

    /**
     * Creates a new instance.
     *
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
             Function.identity(),
             Function.identity(),
             Function.identity(),
             Sampler.always());
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
            Function<? super RequestHeaders, ?> requestHeadersSanitizer,
            Function<Object, ?> requestContentSanitizer,
            Function<? super HttpHeaders, ?> requestTrailersSanitizer,
            Function<? super ResponseHeaders, ?> responseHeadersSanitizer,
            Function<Object, ?> responseContentSanitizer,
            Function<? super HttpHeaders, ?> responseTrailersSanitizer,
            Function<? super Throwable, ?> responseCauseSanitizer,
            Sampler<? super ServiceRequestContext> sampler) {
        super(requireNonNull(delegate, "delegate"));
        this.requestLogLevel = requireNonNull(requestLogLevel, "requestLogLevel");
        this.successfulResponseLogLevel =
                requireNonNull(successfulResponseLogLevel, "successfulResponseLogLevel");
        this.failedResponseLogLevel = requireNonNull(failedResponseLogLevel, "failedResponseLogLevel");
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
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        if (sampler.isSampled(ctx)) {
            ctx.log().addListener(log -> logRequest(((ServiceRequestContext) log.context()).logger(),
                                                    log, requestLogLevel, requestHeadersSanitizer,
                                                    requestContentSanitizer, requestTrailersSanitizer),
                                  RequestLogAvailability.REQUEST_END);
            ctx.log().addListener(log -> logResponse(((ServiceRequestContext) log.context()).logger(), log,
                                                     requestLogLevel,
                                                     requestHeadersSanitizer,
                                                     requestContentSanitizer,
                                                     requestTrailersSanitizer,
                                                     successfulResponseLogLevel,
                                                     failedResponseLogLevel,
                                                     responseHeadersSanitizer,
                                                     responseContentSanitizer,
                                                     responseTrailersSanitizer,
                                                     responseCauseSanitizer),
                                  RequestLogAvailability.COMPLETE);
        }
        return delegate().serve(ctx, req);
    }
}
