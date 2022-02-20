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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.logging.LoggingDecoratorBuilder;
import com.linecorp.armeria.common.util.Sampler;

/**
 * Builds a new {@link LoggingClient}.
 */
abstract class AbstractLoggingClientBuilder extends LoggingDecoratorBuilder {

    private Sampler<? super ClientRequestContext> sampler = Sampler.always();

    private Sampler<? super ClientRequestContext> failedSampler = Sampler.always();

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged.
     */
    public AbstractLoggingClientBuilder samplingRate(float samplingRate) {
        checkArgument(0.0 <= samplingRate && samplingRate <= 1.0,
                      "samplingRate: %s (expected: 0.0 <= samplingRate <= 1.0)", samplingRate);
        return sampler(Sampler.random(samplingRate));
    }

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the failed requests to be logged.
     */
    public AbstractLoggingClientBuilder failedSamplingRate(float failedSamplingRate) {
        checkArgument(0.0 <= failedSamplingRate && failedSamplingRate <= 1.0,
                      "failedSamplingRate: %s (expected: 0.0 <= failedSamplingRate <= 1.0)",
                      failedSamplingRate);
        return failedSampler(Sampler.random(failedSamplingRate));
    }

    /**
     * Sets the {@link Sampler} that determines which request needs logging.
     */
    public AbstractLoggingClientBuilder sampler(Sampler<? super ClientRequestContext> sampler) {
        this.sampler = requireNonNull(sampler, "sampler");
        return this;
    }

    final Sampler<? super ClientRequestContext> sampler() {
        return sampler;
    }

    /**
     * Sets the {@link Sampler} that determines which failed request needs logging.
     */
    public AbstractLoggingClientBuilder failedSampler(
            Sampler<? super ClientRequestContext> failedSampler) {
        this.failedSampler = requireNonNull(failedSampler, "failedSampler");
        return this;
    }

    final Sampler<? super ClientRequestContext> failedSampler() {
        return failedSampler;
    }
}
