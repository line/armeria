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

    private Sampler<? super ClientRequestContext> successSampler = Sampler.always();

    private Sampler<? super ClientRequestContext> failureSampler = Sampler.always();

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged.
     * This method sets both success and failure, if you want to specify different values for the success
     * and failure samplers, use {@link #successSamplingRate(float)} and
     * {@link #failureSamplingRate(float)} instead.
     */
    public AbstractLoggingClientBuilder samplingRate(float samplingRate) {
        checkArgument(0.0 <= samplingRate && samplingRate <= 1.0,
                      "samplingRate: %s (expected: 0.0 <= samplingRate <= 1.0)", samplingRate);
        return sampler(Sampler.random(samplingRate));
    }

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the failure requests to be logged.
     * This method sets both success and failure, if you want to specify different values for the success
     * and failure samplers, use {@link #successSampler(Sampler)} and * {@link #failureSampler(Sampler)}
     * instead.
     */
    public AbstractLoggingClientBuilder failureSamplingRate(float failureSamplingRate) {
        checkArgument(0.0 <= failureSamplingRate && failureSamplingRate <= 1.0,
                      "failureSamplingRate: %s (expected: 0.0 <= failureSamplingRate <= 1.0)",
                      failureSamplingRate);
        return failureSampler(Sampler.random(failureSamplingRate));
    }

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the success requests to be logged.
     */
    public AbstractLoggingClientBuilder successSamplingRate(float successSamplingRate) {
        checkArgument(0.0 <= successSamplingRate && successSamplingRate <= 1.0,
                      "successSamplingRate: %s (expected: 0.0 <= successSamplingRate <= 1.0)",
                      successSamplingRate);
        return successSampler(Sampler.random(successSamplingRate));
    }

    /**
     * Sets the {@link Sampler} that determines which request needs logging.
     */
    public AbstractLoggingClientBuilder sampler(Sampler<? super ClientRequestContext> sampler) {
        requireNonNull(sampler, "sampler");
        this.successSampler = sampler;
        this.failureSampler = sampler;
        return this;
    }

    /**
     * Sets the {@link Sampler} that determines which failure request needs logging.
     */
    public AbstractLoggingClientBuilder failureSampler(
            Sampler<? super ClientRequestContext> failureSampler) {
        this.failureSampler = requireNonNull(failureSampler, "failureSampler");
        return this;
    }

    final Sampler<? super ClientRequestContext> failureSampler() {
        return failureSampler;
    }

    /**
     * Sets the {@link Sampler} that determines which success request needs logging.
     */
    public AbstractLoggingClientBuilder successSampler(
            Sampler<? super ClientRequestContext> successSampler) {
        this.successSampler = requireNonNull(successSampler, "successSampler");
        return this;
    }

    final Sampler<? super ClientRequestContext> successSampler() {
        return successSampler;
    }
}
