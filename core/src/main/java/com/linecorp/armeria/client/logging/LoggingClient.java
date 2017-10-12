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

package com.linecorp.armeria.client.logging;

import static com.linecorp.armeria.internal.logging.LoggingDecorators.logRequest;
import static com.linecorp.armeria.internal.logging.LoggingDecorators.logResponse;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.logging.Sampler;
import com.linecorp.armeria.server.logging.LoggingService;

/**
 * Decorates a {@link Client} to log {@link Request}s and {@link Response}s.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public final class LoggingClient<I extends Request, O extends Response> extends SimpleDecoratingClient<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(LoggingClient.class);

    private static final String REQUEST_FORMAT = "{} Request: {}";
    private static final String RESPONSE_FORMAT = "{} Response: {}";

    /**
     * Returns a new {@link Client} decorator that logs {@link Request}s and {@link Response}s at
     * {@link LogLevel#INFO} for success, {@link LogLevel#WARN} for failure.
     *
     * @see LoggingClientBuilder for more information on the default settings.
     */
    public static <I extends Request, O extends Response>
    Function<Client<I, O>, LoggingClient<I, O>> newDecorator() {
        return new LoggingClientBuilder()
                .requestLogLevel(LogLevel.INFO)
                .successfulResponseLogLevel(LogLevel.INFO)
                .failureResponseLogLevel(LogLevel.WARN)
                .newDecorator();
    }

    /**
     * Returns a new {@link Client} decorator that logs {@link Request}s and {@link Response}s.
     *
     * @param level the log level
     * @deprecated Use {@link LoggingClientBuilder}.
     */
    @Deprecated
    public static <I extends Request, O extends Response>
    Function<Client<I, O>, LoggingClient<I, O>> newDecorator(LogLevel level) {
        return delegate -> new LoggingClient<>(delegate, level);
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
     * Creates a new instance that logs {@link Request}s and {@link Response}s at {@link LogLevel#INFO}.
     *
     * @deprecated Use {@link LoggingService#newDecorator()}.
     */
    @Deprecated
    public LoggingClient(Client<I, O> delegate) {
        this(delegate, LogLevel.INFO);
    }

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}.
     *
     * @deprecated Use {@link LoggingClientBuilder}.
     */
    @Deprecated
    public LoggingClient(Client<I, O> delegate, LogLevel level) {
        this(delegate,
             level,
             level,
             level,
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
    LoggingClient(Client<I, O> delegate,
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
        this.successfulResponseLogLevel = requireNonNull(successfulResponseLogLevel,
                                                         "successfulResponseLogLevel");
        this.failedResponseLogLevel = requireNonNull(failedResponseLogLevel, "failedResponseLogLevel");
        this.requestHeadersSanitizer = requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        this.responseHeadersSanitizer = requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "resposneContentSanitizer");
        this.sampler = requireNonNull(sampler, "sampler");
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        if (sampler.isSampled()) {
            ctx.log().addListener(log -> logRequest(logger, log, requestLogLevel,
                                                    requestHeadersSanitizer, requestContentSanitizer),
                                  RequestLogAvailability.REQUEST_END);
            ctx.log().addListener(log -> logResponse(logger, log, successfulResponseLogLevel,
                                                     failedResponseLogLevel, responseHeadersSanitizer,
                                                     responseContentSanitizer),
                                  RequestLogAvailability.COMPLETE);
        }
        return delegate().execute(ctx, req);
    }
}
