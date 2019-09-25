/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LoggingDecoratorBuilder;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Builds a new {@link LoggingService}.
 */
public class LoggingServiceBuilder extends LoggingDecoratorBuilder<LoggingServiceBuilder> {
    private Sampler<? super ServiceRequestContext> sampler = Sampler.always();

    /**
     * Sets the {@link Sampler} that determines which request needs logging.
     */
    public LoggingServiceBuilder sampler(Sampler<? super ServiceRequestContext> sampler) {
        this.sampler = requireNonNull(sampler, "sampler");
        return this;
    }

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged.
     */
    public LoggingServiceBuilder samplingRate(float samplingRate) {
        checkArgument(0.0 <= samplingRate && samplingRate <= 1.0,
                      "samplingRate: %s (expected: 0.0 <= samplingRate <= 1.0)", samplingRate);
        return sampler(Sampler.random(samplingRate));
    }

    /**
     * Returns a newly-created {@link LoggingService} decorating {@code delegate} based on the properties of
     * this builder.
     */
    public <I extends Request, O extends Response> LoggingService<I, O> build(Service<I, O> delegate) {
        return new LoggingService<>(delegate,
                                    requestLogLevel(),
                                    successfulResponseLogLevel(),
                                    failedResponseLogLevel(),
                                    requestHeadersSanitizer(),
                                    requestContentSanitizer(),
                                    requestTrailersSanitizer(),
                                    responseHeadersSanitizer(),
                                    responseContentSanitizer(),
                                    responseTrailersSanitizer(),
                                    responseCauseSanitizer(),
                                    sampler);
    }

    /**
     * Returns a newly-created {@link LoggingService} decorator based on the properties of this builder.
     */
    public <I extends Request, O extends Response> Function<Service<I, O>, LoggingService<I, O>>
    newDecorator() {
        return this::build;
    }
}
