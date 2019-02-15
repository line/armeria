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

/**
 * Builds a new logging decorator.
 */
public abstract class LoggingDecoratorBuilder<T extends LoggingDecoratorBuilder<T>> {
    private static final Function<HttpHeaders, HttpHeaders> DEFAULT_HEADERS_SANITIZER = Function.identity();
    private static final Function<Object, Object> DEFAULT_CONTENT_SANITIZER = Function.identity();
    private static final Function<Throwable, Throwable> DEFAULT_CAUSE_SANITIZER = Function.identity();

    private LogLevel requestLogLevel = LogLevel.TRACE;
    private LogLevel successfulResponseLogLevel = LogLevel.TRACE;
    private LogLevel failedResponseLogLevel = LogLevel.WARN;
    private Function<? super HttpHeaders, ? extends HttpHeaders> requestHeadersSanitizer =
            DEFAULT_HEADERS_SANITIZER;
    private Function<Object, ?> requestContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private Function<? super HttpHeaders, ? extends HttpHeaders> responseHeadersSanitizer =
            DEFAULT_HEADERS_SANITIZER;
    private Function<Object, ?> responseContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private Function<? super Throwable, ? extends Throwable> responseCauseSanitizer = DEFAULT_CAUSE_SANITIZER;
    private float samplingRate = 1.0f;

    /**
     * Sets the {@link LogLevel} to use when logging requests. If unset, will use {@link LogLevel#TRACE}.
     */
    public T requestLogLevel(LogLevel requestLogLevel) {
        this.requestLogLevel = requireNonNull(requestLogLevel, "requestLogLevel");
        return self();
    }

    /**
     * Returns the {@link LogLevel} to use when logging requests.
     */
    protected LogLevel requestLogLevel() {
        return requestLogLevel;
    }

    /**
     * Sets the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     * If unset, will use {@link LogLevel#TRACE}.
     */
    public T successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        this.successfulResponseLogLevel =
                requireNonNull(successfulResponseLogLevel, "successfulResponseLogLevel");
        return self();
    }

    /**
     * Returns the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     */
    protected LogLevel successfulResponseLogLevel() {
        return successfulResponseLogLevel;
    }

    /**
     * Sets the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     * If unset, will use {@link LogLevel#WARN}. The request will be logged too if it was not otherwise.
     */
    public T failureResponseLogLevel(LogLevel failedResponseLogLevel) {
        this.failedResponseLogLevel = requireNonNull(failedResponseLogLevel, "failedResponseLogLevel");
        return self();
    }

    /**
     * Returns the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     */
    protected LogLevel failedResponseLogLevel() {
        return failedResponseLogLevel;
    }

    /**
     * Sets the {@link Function} to use to sanitize request headers before logging. It is common to have the
     * {@link Function} that removes sensitive headers, like {@code Cookie}, before logging. If unset, will use
     * {@link Function#identity()}.
     */
    public T requestHeadersSanitizer(
            Function<? super HttpHeaders, ? extends HttpHeaders> requestHeadersSanitizer) {
        this.requestHeadersSanitizer = requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        return self();
    }

    /**
     * Returns the {@link Function} to use to sanitize request headers before logging.
     */
    protected Function<? super HttpHeaders, ? extends HttpHeaders> requestHeadersSanitizer() {
        return requestHeadersSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize response headers before logging. It is common to have the
     * {@link Function} that removes sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public T responseHeadersSanitizer(
            Function<? super HttpHeaders, ? extends HttpHeaders> responseHeadersSanitizer) {
        this.responseHeadersSanitizer = requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        return self();
    }

    /**
     * Returns the {@link Function} to use to sanitize response headers before logging.
     */
    protected Function<? super HttpHeaders, ? extends HttpHeaders> responseHeadersSanitizer() {
        return responseHeadersSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize request and response headers before logging. It is common
     * to have the {@link Function} that removes sensitive headers, like {@code "Cookie"} and
     * {@code "Set-Cookie"}, before logging. This method is a shortcut of:
     * <pre>{@code
     * builder.requestHeadersSanitizer(headersSanitizer);
     * builder.responseHeadersSanitizer(headersSanitizer);
     * }</pre>
     *
     * @see #requestHeadersSanitizer(Function)
     * @see #responseHeadersSanitizer(Function)
     */
    public T headersSanitizer(Function<? super HttpHeaders, ? extends HttpHeaders> headersSanitizer) {
        requireNonNull(headersSanitizer, "headersSanitizer");
        requestHeadersSanitizer(headersSanitizer);
        responseHeadersSanitizer(headersSanitizer);
        return self();
    }

    /**
     * Sets the {@link Function} to use to sanitize request content before logging. It is common to have the
     * {@link Function} that removes sensitive content, such as an GPS location query, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public T requestContentSanitizer(Function<Object, ?> requestContentSanitizer) {
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        return self();
    }

    /**
     * Returns the {@link Function} to use to sanitize request content before logging.
     */
    protected Function<Object, ?> requestContentSanitizer() {
        return requestContentSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize response content before logging. It is common to have the
     * {@link Function} that removes sensitive content, such as an address, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public T responseContentSanitizer(Function<Object, ?> responseContentSanitizer) {
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "responseContentSanitizer");
        return self();
    }

    /**
     * Returns the {@link Function} to use to sanitize response content before logging.
     */
    protected Function<Object, ?> responseContentSanitizer() {
        return responseContentSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize request and response content before logging. It is common
     * to have the {@link Function} that removes sensitive content, such as an GPS location query and
     * an address, before logging. If unset, will use {@link Function#identity()}. This method is a shortcut of:
     * <pre>{@code
     * builder.requestContentSanitizer(contentSanitizer);
     * builder.responseContentSanitizer(contentSanitizer);
     * }</pre>
     *
     * @see #requestContentSanitizer(Function)
     * @see #responseContentSanitizer(Function)
     */
    public T contentSanitizer(Function<Object, ?> contentSanitizer) {
        requireNonNull(contentSanitizer, "contentSanitizer");
        requestContentSanitizer(contentSanitizer);
        responseContentSanitizer(contentSanitizer);
        return self();
    }

    /**
     * Sets the {@link Function} to use to sanitize a response cause before logging. You can
     * sanitize the stack trace of the exception to remove sensitive information, or prevent from logging
     * the stack trace completely by returning {@code null} in the {@link Function}. If unset, will use
     * {@link Function#identity()}.
     */
    public T responseCauseSanitizer(Function<? super Throwable, ? extends Throwable> responseCauseSanitizer) {
        this.responseCauseSanitizer = requireNonNull(responseCauseSanitizer, "responseCauseSanitizer");
        return self();
    }

    /**
     * Returns the {@link Function} to use to sanitize response cause before logging.
     */
    protected Function<? super Throwable, ? extends Throwable> responseCauseSanitizer() {
        return responseCauseSanitizer;
    }

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged. The random sampling is appropriate for low-traffic
     * (ex servers that each receive &lt;100K requests). If unset, all requests will be logged.
     */
    public T samplingRate(float samplingRate) {
        checkArgument(0.0 <= samplingRate && samplingRate <= 1.0, "samplingRate must be between 0.0 and 1.0");
        this.samplingRate = samplingRate;
        return self();
    }

    /**
     * Returns the rate at which to sample requests to log.
     */
    protected float samplingRate() {
        return samplingRate;
    }

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }

    @Override
    public String toString() {
        return toString(this, requestLogLevel, successfulResponseLogLevel, failedResponseLogLevel,
                        requestHeadersSanitizer, requestContentSanitizer, responseHeadersSanitizer,
                        responseContentSanitizer, samplingRate);
    }

    private static <T extends LoggingDecoratorBuilder<T>> String toString(
            LoggingDecoratorBuilder<T> self,
            LogLevel requestLogLevel,
            LogLevel successfulResponseLogLevel,
            LogLevel failureResponseLogLevel,
            Function<? super HttpHeaders, ? extends HttpHeaders> requestHeadersSanitizer,
            Function<?, ?> requestContentSanitizer,
            Function<? super HttpHeaders, ? extends HttpHeaders> responseHeadersSanitizer,
            Function<?, ?> responseContentSanitizer,
            float samplingRate) {
        final ToStringHelper helper = MoreObjects.toStringHelper(self)
                                                 .add("requestLogLevel", requestLogLevel)
                                                 .add("successfulResponseLogLevel", successfulResponseLogLevel)
                                                 .add("failedResponseLogLevel", failureResponseLogLevel)
                                                 .add("samplingRate", samplingRate);
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
