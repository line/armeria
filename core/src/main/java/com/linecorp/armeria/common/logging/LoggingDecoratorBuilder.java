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

package com.linecorp.armeria.common.logging;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.internal.logging.Sampler;

/**
 * Builds a new logging decorator.
 */
public class LoggingDecoratorBuilder<T extends LoggingDecoratorBuilder<T>> {
    private static final Function<HttpHeaders, HttpHeaders> DEFAULT_HEADERS_SANITIZER = Function.identity();
    private static final Function<Object, Object> DEFAULT_CONTENT_SANITIZER = Function.identity();

    private LogLevel requestLogLevel = LogLevel.TRACE;
    private LogLevel successfulResponseLogLevel = LogLevel.TRACE;
    private LogLevel failedResponseLogLevel = LogLevel.WARN;
    private Function<HttpHeaders, HttpHeaders> requestHeadersSanitizer = DEFAULT_HEADERS_SANITIZER;
    private Function<Object, Object> requestContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private Function<HttpHeaders, HttpHeaders> responseHeadersSanitizer = DEFAULT_HEADERS_SANITIZER;
    private Function<Object, Object> responseContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private Sampler sampler = Sampler.always();

    /**
     * Sets the {@link LogLevel} to use when logging requests. If unset, will use {@link LogLevel#TRACE}.
     */
    public T requestLogLevel(LogLevel requestLogLevel) {
        this.requestLogLevel = requireNonNull(requestLogLevel, "requestLogLevel");
        return unsafeCast(this);
    }

    /**
     * Returns the {@link LogLevel} to use when logging requests.
     */
    public LogLevel getRequestLogLevel() {
        return requestLogLevel;
    }

    /**
     * Sets the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     * If unset, will use {@link LogLevel#TRACE}.
     */
    public T successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        this.successfulResponseLogLevel =
                requireNonNull(successfulResponseLogLevel, "successfulResponseLogLevel");
        return unsafeCast(this);
    }

    /**
     * Returns the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     */
    public LogLevel getSuccessfulResponseLogLevel() {
        return successfulResponseLogLevel;
    }

    /**
     * Sets the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     * If unset, will use {@link LogLevel#WARN}.
     */
    public T failureResponseLogLevel(LogLevel failedResponseLogLevel) {
        this.failedResponseLogLevel = requireNonNull(failedResponseLogLevel, "failedResponseLogLevel");
        return unsafeCast(this);
    }

    /**
     * Returns the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     */
    public LogLevel getFailedResponseLogLevel() {
        return failedResponseLogLevel;
    }

    /**
     * Sets the {@link Function} to use to sanitize request headers before logging. It is common to have the
     * {@link Function} remove sensitive headers, like {@code Cookie}, before logging. If unset, will use
     * {@link Function#identity()}.
     */
    public T requestHeadersSanitizer(Function<HttpHeaders, HttpHeaders> requestHeadersSanitizer) {
        this.requestHeadersSanitizer = requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        return unsafeCast(this);
    }

    /**
     * Returns the {@link Function} to use to sanitize request headers before logging.
     */
    public Function<HttpHeaders, HttpHeaders> getRequestHeadersSanitizer() {
        return requestHeadersSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize request content before logging. It is common to have the
     * {@link Function} remove sensitive content, such as an GPS location query, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public T requestContentSanitizer(Function<Object, Object> requestContentSanitizer) {
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        return unsafeCast(this);
    }

    /**
     * Returns the {@link Function} to use to sanitize request content before logging.
     */
    public Function<Object, Object> getRequestContentSanitizer() {
        return requestContentSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize response headers before logging. It is common to have the
     * {@link Function} remove sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public T responseHeadersSanitizer(Function<HttpHeaders, HttpHeaders> responseHeadersSanitizer) {
        this.responseHeadersSanitizer = requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        return unsafeCast(this);
    }

    /**
     * Returns the {@link Function} to use to sanitize response headers before logging.
     */
    public Function<HttpHeaders, HttpHeaders> getResponseHeadersSanitizer() {
        return responseHeadersSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize response content before logging. It is common to have the
     * {@link Function} remove sensitive content, such as an address, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public T responseContentSanitizer(Function<Object, Object> responseContentSanitizer) {
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "responseContentSanitizer");
        return unsafeCast(this);
    }

    /**
     * Returns the {@link Function} to use to sanitize response content before logging.
     */
    public Function<Object, Object> getResponseContentSanitizer() {
        return responseContentSanitizer;
    }

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged. The random sampling is appropriate for low-traffic
     * (ex servers that each receive &lt;100K requests). If unset, all requests will be logged.
     */
    public T samplingRate(float samplingRate) {
        checkArgument(0.0 <= samplingRate && samplingRate <= 1.0, "samplingRate must be between 0.0 and 1.0");
        sampler = Sampler.create(samplingRate);
        return unsafeCast(this);
    }

    /**
     * Returns the {@link Sampler} at which to sample requests to log.
     */
    public Sampler getSamplingRate() {
        return sampler;
    }

    @SuppressWarnings("unchecked")
    private T unsafeCast(LoggingDecoratorBuilder<T> self) {
        return (T) self;
    }

    @Override
    public String toString() {
        return toString(this, requestLogLevel, successfulResponseLogLevel, failedResponseLogLevel,
                        requestHeadersSanitizer, requestContentSanitizer, responseHeadersSanitizer,
                        responseContentSanitizer, sampler);
    }

    private static <T extends LoggingDecoratorBuilder<T>> String toString(
            LoggingDecoratorBuilder<T> self,
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
                                                 .add("failedResponseLogLevel", failureResponseLogLevel)
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
