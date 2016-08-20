/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.common.ContentTooLargeException;
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
     * Returns the {@link Service} that is handling the current {@link Request}.
     */
    <T extends Service<?, ?>> T service();

    /**
     * Returns the {@link ExecutorService} that could be used for executing a potentially long-running task.
     * Note that performing a long-running task in {@link Service#serve(ServiceRequestContext, Request)} may
     * block the {@link Server}'s I/O event loop and thus should be executed in other threads.
     */
    ExecutorService blockingTaskExecutor();

    /**
     * Returns the remote address of this invocation.
     */
    <A extends SocketAddress> A remoteAddress();

    /**
     * Returns the local address of this invocation.
     */
    <A extends SocketAddress> A localAddress();

    /**
     * Returns the path with its context path removed. This method can be useful for a reusable service bound
     * at various path prefixes. For client side invocations, this method always returns the same value as
     * {@link #path()}.
     */
    String mappedPath();

    /**
     * Returns the {@link Logger}  which logs information about this invocation as the prefix of log messages.
     * e.g. If a user called {@code ctx.logger().info("Hello")},
     * <pre>{@code
     * [id: 0x270781f4, /127.0.0.1:63466 => /127.0.0.1:63432][tbinary+h2c://example.com/path#method][42] Hello
     * }</pre>
     */
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
