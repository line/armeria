/*
 * Copyright 2017 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Decorates a {@link Service} to log {@link Request}s and {@link Response}s.
 * Same as {@link LoggingService} but can configure what kind of data will be logging.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public class SampledLoggingService<I extends Request, O extends Response> extends LoggingService<I, O> {
    private final boolean logRequest;
    private final boolean logResponse;
    private final Sampler sampler;

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at {@link LogLevel#INFO}.
     */
    public static <I extends Request, O extends Response> Function<Service<I, O>, SampledLoggingService<I, O>>
    newDecorator(float logSamplingRate) {
        return newDecorator(true, true, logSamplingRate);
    }

    /**
     * Creates a new instance that logs {@link Request}s at {@link LogLevel#INFO} if {@code logRequest} is
     * {@code true} and logs {@link Response}s at {@link LogLevel#INFO} if {@code logRequest} is {@code true}.
     */
    public static <I extends Request, O extends Response> Function<Service<I, O>, SampledLoggingService<I, O>>
    newDecorator(boolean logRequest, boolean logResponse, float logSamplingRate) {
        return service -> new SampledLoggingService<>(service, LogLevel.INFO, logRequest, logResponse,
                                                      logSamplingRate);
    }

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}.
     */
    public SampledLoggingService(Service<I, O> delegate, LogLevel level,
                                 boolean logRequest, boolean logResponse, float logSamplingRate) {
        super(delegate, level);
        this.logRequest = logRequest;
        this.logResponse = logResponse;
        sampler = CountingSampler.create(logSamplingRate);
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        if (sampler.isSampled()) {
            if (logRequest) {
                ctx.log().addListener(this::logRequest, RequestLogAvailability.REQUEST_END);
            }
            if (logResponse) {
                ctx.log().addListener(this::logResponse, RequestLogAvailability.COMPLETE);
            }
        }
        return delegate().serve(ctx, req);
    }
}
