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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link LoggingService}.
 */
public class LoggingServiceBuilder {

    private static final Function<HttpHeaders, HttpHeaders> DEFAULT_HEADERS_SANITIZER = Function.identity();
    private static final Function<Object, Object> DEFAULT_CONTENT_SANITIZER = Function.identity();

    private LogLevel requestLogLevel = LogLevel.TRACE;
    private LogLevel successfulResponseLogLevel = LogLevel.TRACE;
    private LogLevel failureResponseLogLevel = LogLevel.WARN;
    private Function<HttpHeaders, HttpHeaders> requestHeadersSanitizer = DEFAULT_HEADERS_SANITIZER;
    private Function<Object, Object> requestContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private Function<HttpHeaders, HttpHeaders> responseHeadersSanitizer = DEFAULT_HEADERS_SANITIZER;
    private Function<Object, Object> responseContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private Sampler sampler = Sampler.ALWAYS_SAMPLE;

    /**
     * Sets the {@link LogLevel} to use when logging requests. If unset, will use {@link LogLevel#TRACE}.
     */
    public LoggingServiceBuilder requestLogLevel(LogLevel requestLogLevel) {
        this.requestLogLevel = requireNonNull(requestLogLevel, "requestLogLevel");
        return this;
    }

    /**
     * Sets the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     * If unset, will use {@link LogLevel#TRACE}.
     */
    public LoggingServiceBuilder successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        this.successfulResponseLogLevel =
                requireNonNull(successfulResponseLogLevel, "successfulResponseLogLevel");
        return this;
    }

    /**
     * Sets the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     * If unset, will use {@link LogLevel#WARN}.
     */
    public LoggingServiceBuilder failureResponseLogLevel(LogLevel failureResponseLogLevel) {
        this.failureResponseLogLevel = requireNonNull(failureResponseLogLevel, "failureResponseLogLevel");
        return this;
    }

    /**
     * Sets the {@link Function} to use to sanitize request headers before logging. It is common to have the
     * {@link Function} remove sensitive headers, like {@code Cookie}, before logging. If unset, will use
     * {@link Function#identity()}.
     */
    public LoggingServiceBuilder requestHeadersSanitizer(
            Function<HttpHeaders, HttpHeaders> requestHeadersSanitizer) {
        this.requestHeadersSanitizer = requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        return this;
    }

    /**
     * Sets the {@link Function} to use to sanitize request content before logging. It is common to have the
     * {@link Function} remove sensitive content, such as an GPS location query, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public LoggingServiceBuilder requestContentSanitizer(Function<Object, Object> requestContentSanitizer) {
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        return this;
    }

    /**
     * Sets the {@link Function} to use to sanitize response headers before logging. It is common to have the
     * {@link Function} remove sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public LoggingServiceBuilder responseHeadersSanitizer(
            Function<HttpHeaders, HttpHeaders> responseHeadersSanitizer) {
        this.responseHeadersSanitizer = requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        return this;
    }

    /**
     * Sets the {@link Function} to use to sanitize response content before logging. It is common to have the
     * {@link Function} remove sensitive content, such as an address, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public LoggingServiceBuilder responseContentSanitizer(Function<Object, Object> responseContentSanitizer) {
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "responseContentSanitizer");
        return this;
    }

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged. The random sampling is appropriate for low-traffic
     * (ex servers that each receive &lt;100K requests). If unset, all requests will be logged.
     */
    public LoggingServiceBuilder samplingRate(float samplingRate) {
        checkArgument(0.0 <= samplingRate && samplingRate <= 1.0, "samplingRate must be between 0.0 and 1.0");
        this.sampler = Sampler.create(samplingRate);
        return this;
    }

    /**
     * Returns a newly-created {@link LoggingService} decorator based on the properties of this builder.
     */
    public <I extends Request, O extends Response> Function<Service<I, O>, LoggingService<I, O>>
    buildDecorator() {
        return delegate -> new LoggingService<>(
                delegate,
                requestLogLevel,
                successfulResponseLogLevel,
                failureResponseLogLevel,
                requestHeadersSanitizer,
                requestContentSanitizer,
                responseHeadersSanitizer,
                responseContentSanitizer,
                sampler);
    }

    @Override
    public String toString() {
        return toString(this, requestLogLevel, successfulResponseLogLevel, failureResponseLogLevel,
                        requestHeadersSanitizer, requestContentSanitizer, responseHeadersSanitizer,
                        responseContentSanitizer, sampler);
    }

    static String toString(
            LoggingServiceBuilder self,
            LogLevel requestLogLevel,
            LogLevel successfulResponseLogLevel,
            LogLevel failureResponseLogLevel,
            Function<HttpHeaders, HttpHeaders> requestHeadersSanitizer,
            Function<Object, Object> requestContentSanitizer,
            Function<HttpHeaders, HttpHeaders> responseHeadersSanitizer,
            Function<Object, Object> responseContentSanitizer,
            Sampler sampler) {
        final ToStringHelper helper = MoreObjects.toStringHelper(self)
                .add("requestLogLevel", requestLogLevel)
                .add("successfulResponseLogLevel", successfulResponseLogLevel)
                .add("failureResponseLogLevel", failureResponseLogLevel)
                .add("sampler", sampler);
        if (requestHeadersSanitizer != DEFAULT_HEADERS_SANITIZER) {
            helper.add("requestHeadersSanitizer", requestHeadersSanitizer);
        }
        if (requestContentSanitizer != DEFAULT_CONTENT_SANITIZER) {
            helper.add("requestContentSanitizer", requestContentSanitizer);
        }
        if (responseHeadersSanitizer != DEFAULT_HEADERS_SANITIZER) {
            helper.add("responseHeadersSanitizer", responseHeadersSanitizer);
        }
        if (responseContentSanitizer != DEFAULT_CONTENT_SANITIZER) {
            helper.add("responseContentSanitizer", responseContentSanitizer);
        }
        return helper.toString();
    }
}
