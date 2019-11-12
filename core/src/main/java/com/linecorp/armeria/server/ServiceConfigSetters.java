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

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.logging.AccessLogWriter;

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
     * Sets the {@link ContentPreviewerFactory} for an HTTP request of a {@link Service}.
     * If not set, the {@link ContentPreviewerFactory} set via
     * {@link VirtualHost#requestContentPreviewerFactory()} is used.
     */
    ServiceConfigSetters requestContentPreviewerFactory(ContentPreviewerFactory factory);

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP response of a {@link Service}.
     * If not set, the {@link ContentPreviewerFactory} set via
     * {@link VirtualHost#responseContentPreviewerFactory()} is used.
     */
    ServiceConfigSetters responseContentPreviewerFactory(ContentPreviewerFactory factory);

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for an HTTP request/response of the {@link Service}.
     * The previewer is enabled only if the content type of an HTTP request/response meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview.
     */
    ServiceConfigSetters contentPreview(int length);

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for an HTTP request/response of a {@link Service}.
     * The previewer is enabled only if the content type of an HTTP request/response meets any of
     * the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    ServiceConfigSetters contentPreview(int length, Charset defaultCharset);

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP request/response of a {@link Service}.
     */
    ServiceConfigSetters contentPreviewerFactory(ContentPreviewerFactory factory);

    /**
     * Sets the format of this {@link Service}'s access log. The specified {@code accessLogFormat} would be
     * parsed by {@link AccessLogWriter#custom(String)}.
     */
    ServiceConfigSetters accessLogFormat(String accessLogFormat);

    /**
     * Sets the access log writer of this {@link Service}. If not set, the {@link AccessLogWriter} set via
     * {@link VirtualHost#accessLogWriter()} is used.
     *
     * @param shutdownOnStop whether to shut down the {@link AccessLogWriter} when the {@link Server} stops
     */
    ServiceConfigSetters accessLogWriter(AccessLogWriter accessLogWriter,
                                         boolean shutdownOnStop);

    /**
     * Decorates a {@link Service} with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates the {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    ServiceConfigSetters decorator(Function<T, R> decorator);
}
