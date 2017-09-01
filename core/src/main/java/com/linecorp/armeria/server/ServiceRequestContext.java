/*
 * Copyright 2016 LINE Corporation
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
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;

/**
 * Provides information about an invocation and related utilities. Every request being handled has its own
 * {@link ServiceRequestContext} instance.
 */
public interface ServiceRequestContext extends RequestContext {

    /**
     * Returns the {@link Server} that is handling the current {@link Request}.
     */
    Server server();

    /**
     * Returns the {@link VirtualHost} that is handling the current {@link Request}.
     */
    VirtualHost virtualHost();

    /**
     * Returns the {@link PathMapping} associated with the {@link Service} that is handling the current
     * {@link Request}.
     */
    PathMapping pathMapping();

    /**
     * Returns the {@link PathMappingContext} used to find the {@link Service}.
     */
    PathMappingContext pathMappingContext();

    /**
     * Returns the path parameters mapped by the {@link PathMapping} associated with the {@link Service}
     * that is handling the current {@link Request}.
     */
    Map<String, String> pathParams();

    /**
     * Returns the value of the specified path parameter.
     */
    default String pathParam(String name) {
        return pathParams().get(name);
    }

    /**
     * Returns the {@link Service} that is handling the current {@link Request}.
     */
    <T extends Service<HttpRequest, HttpResponse>> T service();

    /**
     * Returns the {@link ExecutorService} that could be used for executing a potentially long-running task.
     * The {@link ExecutorService} will propagate the {@link ServiceRequestContext} automatically when running
     * a task.
     *
     * <p>Note that performing a long-running task in {@link Service#serve(ServiceRequestContext, Request)}
     * may block the {@link Server}'s I/O event loop and thus should be executed in other threads.
     */
    ExecutorService blockingTaskExecutor();

    /**
     * Returns the path with its context path removed. This method can be useful for a reusable service bound
     * at various path prefixes.
     */
    String mappedPath();

    /**
     * Returns the negotiated producible media type. If the media type negotiation is not used for the
     * {@link Service}, {@code null} would be returned.
     */
    @Nullable
    MediaType negotiatedProduceType();

    /**
     * Returns the {@link Logger} of the {@link Service}.
     *
     * @deprecated Use a logging framework integration such as {@code RequestContextExportingAppender} in
     *             {@code armeria-logback}.
     */
    @Deprecated
    Logger logger();

    /**
     * Returns the amount of time allowed until receiving the current {@link Request} completely.
     * This value is initially set from {@link ServerConfig#defaultRequestTimeoutMillis()}.
     */
    long requestTimeoutMillis();

    /**
     * Sets the amount of time allowed until receiving the current {@link Request} completely.
     * This value is initially set from {@link ServerConfig#defaultRequestTimeoutMillis()}.
     */
    void setRequestTimeoutMillis(long requestTimeoutMillis);

    /**
     * Sets the amount of time allowed until receiving the current {@link Request} completely.
     * This value is initially set from {@link ServerConfig#defaultRequestTimeoutMillis()}.
     */
    void setRequestTimeout(Duration requestTimeout);

    /**
     * Sets a handler to run when the request times out. {@code requestTimeoutHandler} must close the response,
     * e.g., by calling {@link HttpResponseWriter#respond(int)}. If not set, the response will be closed with
     * {@link HttpStatus#SERVICE_UNAVAILABLE}.
     *
     * <p>For example,
     * <pre>{@code
     *   DefaultHttpResponse res = new DefaultHttpResponse();
     *   ctx.setRequestTimeoutHandler(() -> {
     *      res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Request timed out.");
     *   });
     *   ...
     * }</pre>
     */
    void setRequestTimeoutHandler(Runnable requestTimeoutHandler);

    /**
     * Returns the maximum length of the current {@link Request}.
     * This value is initially set from {@link ServerConfig#defaultMaxRequestLength()}.
     *
     * @see ContentTooLargeException
     */
    long maxRequestLength();

    /**
     * Sets the maximum length of the current {@link Request}.
     * This value is initially set from {@link ServerConfig#defaultMaxRequestLength()}.
     *
     * @see ContentTooLargeException
     */
    void setMaxRequestLength(long maxRequestLength);
}
