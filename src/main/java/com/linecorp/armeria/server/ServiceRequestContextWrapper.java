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

import com.linecorp.armeria.common.RequestContextWrapper;

/**
 * Wraps an existing {@link ServiceRequestContext}.
 */
public class ServiceRequestContextWrapper
        extends RequestContextWrapper<ServiceRequestContext> implements ServiceRequestContext {

    /**
     * Creates a new instance.
     */
    protected ServiceRequestContextWrapper(ServiceRequestContext delegate) {
        super(delegate);
    }

    @Override
    public Server server() {
        return delegate().server();
    }

    @Override
    public VirtualHost virtualHost() {
        return delegate().virtualHost();
    }

    @Override
    public <T extends Service<?, ?>> T service() {
        return delegate().service();
    }

    @Override
    public ExecutorService blockingTaskExecutor() {
        return delegate().blockingTaskExecutor();
    }

    @Override
    public <A extends SocketAddress> A remoteAddress() {
        return delegate().remoteAddress();
    }

    @Override
    public <A extends SocketAddress> A localAddress() {
        return delegate().localAddress();
    }

    @Override
    public String mappedPath() {
        return delegate().mappedPath();
    }

    @Override
    public Logger logger() {
        return delegate().logger();
    }

    @Override
    public long requestTimeoutMillis() {
        return delegate().requestTimeoutMillis();
    }

    @Override
    public void setRequestTimeoutMillis(long requestTimeoutMillis) {
        delegate().setRequestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public void setRequestTimeout(Duration requestTimeout) {
        delegate().setRequestTimeout(requestTimeout);
    }

    @Override
    public long maxRequestLength() {
        return delegate().maxRequestLength();
    }

    @Override
    public void setMaxRequestLength(long maxRequestLength) {
        delegate().setMaxRequestLength(maxRequestLength);
    }
}
