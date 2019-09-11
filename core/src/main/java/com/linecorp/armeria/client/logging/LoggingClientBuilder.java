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

package com.linecorp.armeria.client.logging;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LoggingDecoratorBuilder;
import com.linecorp.armeria.common.util.Sampler;

/**
 * Builds a new {@link LoggingClient}.
 */
public class LoggingClientBuilder extends LoggingDecoratorBuilder<LoggingClientBuilder> {
    private Sampler<? super ClientRequestContext> sampler = Sampler.always();

    /**
     * Sets the {@link Sampler} that determines which request needs logging.
     */
    public LoggingClientBuilder sampler(Sampler<? super ClientRequestContext> sampler) {
        this.sampler = requireNonNull(sampler, "sampler");
        return this;
    }

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged.
     */
    public LoggingClientBuilder samplingRate(float samplingRate) {
        checkArgument(0.0 <= samplingRate && samplingRate <= 1.0, "samplingRate must be between 0.0 and 1.0");
        return sampler(Sampler.random(samplingRate));
    }

    /**
     * Returns a newly-created {@link LoggingClient} decorating {@code delegate} based on the properties of
     * this builder.
     */
    public <I extends Request, O extends Response> LoggingClient<I, O> build(Client<I, O> delegate) {
        return new LoggingClient<>(delegate,
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
     * Returns a newly-created {@link LoggingClient} decorator based on the properties of this builder.
     */
    public <I extends Request, O extends Response> Function<Client<I, O>, LoggingClient<I, O>>
    newDecorator() {
        return this::build;
    }
}
