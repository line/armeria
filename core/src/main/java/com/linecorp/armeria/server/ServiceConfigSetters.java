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

import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

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
     * Sets the default value of the {@link RequestLog#name()} property which is used when no name was set via
     * {@link RequestLogBuilder#name(String, String)}.
     *
     * @param defaultLogName the default log name.
     */
    ServiceConfigSetters defaultLogName(String defaultLogName);

    /**
     * Specifies whether the {@link Service} is transient or not. It the {@link Service} is transient:
     * <ul>
     *   <li>requests are not taken account of
     *       {@linkplain ServerBuilder#gracefulShutdownTimeout(Duration, Duration) graceful shutdown}.</li>
     *   <li>requests and responses are not logged or recorded by {@link LoggingService},
     *       {@link AccessLogWriter} and {@link MetricCollectingService}.</li>
     * </ul>
     */
    ServiceConfigSetters transientService(boolean transientService);
}
