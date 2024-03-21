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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.netty.channel.EventLoopGroup;

interface ServiceConfigSetters {

    /**
     * Sets the timeout of an HTTP request. If not set, the value set via
     * {@link VirtualHost#requestTimeoutMillis()} is used.
     *
     * @param requestTimeout the timeout. {@code 0} disables the timeout.
     */
    ServiceConfigSetters requestTimeout(Duration requestTimeout);

    /**
     * Sets the timeout of an HTTP request in milliseconds. If not set, the value set via
     * {@link VirtualHost#requestTimeoutMillis()} is used.
     *
     * @param requestTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    ServiceConfigSetters requestTimeoutMillis(long requestTimeoutMillis);

    /**
     * Sets the maximum allowed length of an HTTP request. If not set, the value set via
     * {@link VirtualHost#maxRequestLength()} is used.
     *
     * @param maxRequestLength the maximum allowed length. {@code 0} disables the length limit.
     */
    ServiceConfigSetters maxRequestLength(long maxRequestLength);

    /**
     * Sets whether the verbose response mode is enabled. When enabled, the service response will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the service response will not expose such server-side details to the client.
     * If not set, the value set via {@link VirtualHostBuilder#verboseResponses(boolean)} is used.
     */
    ServiceConfigSetters verboseResponses(boolean verboseResponses);

    /**
     * Sets the format of this {@link HttpService}'s access log. The specified {@code accessLogFormat} would be
     * parsed by {@link AccessLogWriter#custom(String)}.
     */
    ServiceConfigSetters accessLogFormat(String accessLogFormat);

    /**
     * Sets the access log writer of this {@link HttpService}. If not set, the {@link AccessLogWriter} set via
     * {@link VirtualHost#accessLogWriter()} is used.
     *
     * @param shutdownOnStop whether to shut down the {@link AccessLogWriter} when the {@link Server} stops
     */
    ServiceConfigSetters accessLogWriter(AccessLogWriter accessLogWriter,
                                         boolean shutdownOnStop);

    /**
     * Decorates an {@link HttpService} with the specified {@code decorator}.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    default ServiceConfigSetters decorator(DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        requireNonNull(decoratingHttpServiceFunction, "decoratingHttpServiceFunction");
        return decorator(
                delegate -> new FunctionalDecoratingHttpService(delegate, decoratingHttpServiceFunction));
    }

    /**
     * Decorates an {@link HttpService} with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates the {@link HttpService}
     */
    ServiceConfigSetters decorator(Function<? super HttpService, ? extends HttpService> decorator);

    /**
     * Decorates an {@link HttpService} with the given {@code decorators}, in the order of iteration.
     *
     * @param decorators the {@link Function}s that decorate the {@link HttpService}
     */
    ServiceConfigSetters decorators(Function<? super HttpService, ? extends HttpService>... decorators);

    /**
     * Decorates an {@link HttpService} with the given {@code decorators}, in the order of iteration.
     *
     * @param decorators the {@link Function}s that decorate the {@link HttpService}
     */
    ServiceConfigSetters decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators);

    /**
     * Sets the default value of the {@link RequestLog#serviceName()} property which is used when
     * no service name was set via {@link RequestLogBuilder#name(String, String)}.
     *
     * @param defaultServiceName the default service name.
     */
    ServiceConfigSetters defaultServiceName(String defaultServiceName);

    /**
     * Sets the default naming rule for the {@link RequestLog#serviceName()}.
     * If set, the service name will be converted according to given naming rule.
     *
     * @param defaultServiceNaming the default service naming.
     */
    ServiceConfigSetters defaultServiceNaming(ServiceNaming defaultServiceNaming);

    /**
     * Sets the default value of the {@link RequestLog#name()} property which is used when no name was set via
     * {@link RequestLogBuilder#name(String, String)}.
     *
     * @param defaultLogName the default log name.
     */
    ServiceConfigSetters defaultLogName(String defaultLogName);

    /**
     * Sets an {@link ScheduledExecutorService executor} to be used when executing blocking tasks.
     *
     * @param blockingTaskExecutor the {@link ScheduledExecutorService executor} to be used.
     * @param shutdownOnStop whether to shut down the {@link ScheduledExecutorService} when the {@link Server}
     *                       stops.
     */
    ServiceConfigSetters blockingTaskExecutor(ScheduledExecutorService blockingTaskExecutor,
                                              boolean shutdownOnStop);

    /**
     * Sets an {@link BlockingTaskExecutor executor} to be used when executing blocking tasks.
     *
     * @param blockingTaskExecutor the {@link BlockingTaskExecutor executor} to be used.
     * @param shutdownOnStop whether to shut down the {@link BlockingTaskExecutor} when the {@link Server}
     *                       stops.
     */
    ServiceConfigSetters blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                              boolean shutdownOnStop);

    /**
     * Uses a newly created {@link BlockingTaskExecutor} with the specified number of threads dedicated to
     * the execution of blocking tasks or invocations.
     * The {@link BlockingTaskExecutor} will be shut down when the {@link Server} stops.
     *
     * @param numThreads the number of threads in the executor
     */
    ServiceConfigSetters blockingTaskExecutor(int numThreads);

    /**
     * Sets a {@link SuccessFunction} that determines whether a request was handled successfully or not.
     * If unspecified, {@link SuccessFunction#ofDefault()} is used.
     */
    @UnstableApi
    ServiceConfigSetters successFunction(SuccessFunction successFunction);

    /**
     * Sets the amount of time to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to receive additional data even after closing the response.
     * Specify {@link Duration#ZERO} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    @UnstableApi
    ServiceConfigSetters requestAutoAbortDelay(Duration delay);

    /**
     * Sets the amount of time in millis to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to receive additional data even after closing the response.
     * Specify {@code 0} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    @UnstableApi
    ServiceConfigSetters requestAutoAbortDelayMillis(long delayMillis);

    /**
     * Sets the {@link Path} for storing the files uploaded from
     * {@code multipart/form-data} requests.
     *
     * @param multipartUploadsLocation the path of the directory which stores the files.
     */
    @UnstableApi
    ServiceConfigSetters multipartUploadsLocation(Path multipartUploadsLocation);

     /**
      * Sets a {@linkplain EventLoopGroup worker group} to be used when serving a {@link Service}.
      *
      * @param serviceWorkerGroup the {@linkplain ScheduledExecutorService executor} to be used.
      * @param shutdownOnStop whether to shut down the {@link ScheduledExecutorService} when the {@link Server}
      *                       stops.
      */
     @UnstableApi
     ServiceConfigSetters serviceWorkerGroup(EventLoopGroup serviceWorkerGroup,
                                             boolean shutdownOnStop);

     /**
      * Uses a newly created {@link EventLoopGroup} with the specified number of threads dedicated to
      * the execution of service codes.
      * The {@link EventLoopGroup} will be shut down when the {@link Server} stops.
      *
      * @param numThreads the number of threads in the executor
      */
     @UnstableApi
     ServiceConfigSetters serviceWorkerGroup(int numThreads);

    /**
     * Sets the {@link Function} which generates a {@link RequestId}.
     *
     * @param requestIdGenerator the {@link Function} that generates a request ID.
     */
    ServiceConfigSetters requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator);

    /**
     * Adds the default HTTP header for an {@link HttpResponse} served by this {@link Service}.
     *
     * <p>Note that the value could be overridden if the same {@link HttpHeaderNames} are defined in
     * the {@link ResponseHeaders} of the {@link HttpResponse} or
     * {@link ServiceRequestContext#additionalResponseHeaders()}.
     */
    @UnstableApi
    ServiceConfigSetters addHeader(CharSequence name, Object value);

    /**
     * Adds the default HTTP headers for an {@link HttpResponse} served by this {@link Service}.
     *
     * <p>Note that the value could be overridden if the same {@link HttpHeaderNames} are defined in
     * the {@link ResponseHeaders} of the {@link HttpResponse} or
     * {@link ServiceRequestContext#additionalResponseHeaders()}.
     */
    @UnstableApi
    ServiceConfigSetters addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders);

    /**
     * Sets the default HTTP header for an {@link HttpResponse} served by this {@link Service}.
     *
     * <p>Note that the value could be overridden if the same {@link HttpHeaderNames} are defined in
     * the {@link ResponseHeaders} of the {@link HttpResponse} or
     * {@link ServiceRequestContext#additionalResponseHeaders()}.
     */
    @UnstableApi
    ServiceConfigSetters setHeader(CharSequence name, Object value);

    /**
     * Sets the default HTTP headers for an {@link HttpResponse} served by this {@link Service}.
     *
     * <p>Note that the value could be overridden if the same {@link HttpHeaderNames} are defined in
     * the {@link ResponseHeaders} of the {@link HttpResponse} or
     * {@link ServiceRequestContext#additionalResponseHeaders()}.
     */
    @UnstableApi
    ServiceConfigSetters setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders);

    /**
     * Adds the default {@link ServiceErrorHandler} served by this {@link Service}.
     * If multiple handlers are added, the latter is composed with the former using
     * {@link ServiceErrorHandler#orElse(ServiceErrorHandler)}
     *
     * @param serviceErrorHandler the default {@link ServiceErrorHandler}
     */
    ServiceConfigSetters errorHandler(ServiceErrorHandler serviceErrorHandler);

    @UnstableApi
    ServiceConfigSetters contextHook(Supplier<? extends AutoCloseable> contextHook);
}
