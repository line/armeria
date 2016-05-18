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

import static java.util.Objects.requireNonNull;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;

import com.linecorp.armeria.common.AbstractRequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;

import io.netty.channel.EventLoop;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

public class ServiceRequestContextWrapper extends AbstractRequestContext implements ServiceRequestContext {

    private final ServiceRequestContext delegate;

    protected ServiceRequestContextWrapper(ServiceRequestContext delegate) {
        super(requireNonNull(delegate, "delegate").sessionProtocol(),
              delegate.method(), delegate.path(), delegate.request());

        this.delegate = delegate;
    }

    protected final ServiceRequestContext delegate() {
        return delegate;
    }

    @Override
    public ExecutorService blockingTaskExecutor() {
        return delegate().blockingTaskExecutor();
    }

    @Override
    public String mappedPath() {
        return delegate().mappedPath();
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
    public <T extends Service> T service() {
        return delegate().service();
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

    @Override
    public EventLoop eventLoop() {
        return delegate().eventLoop();
    }

    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        return delegate().attr(key);
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        return delegate().hasAttr(key);
    }

    @Override
    public RequestLogBuilder requestLogBuilder() {
        return delegate.requestLogBuilder();
    }

    @Override
    public ResponseLogBuilder responseLogBuilder() {
        return delegate.responseLogBuilder();
    }

    @Override
    public CompletableFuture<RequestLog> awaitRequestLog() {
        return delegate.awaitRequestLog();
    }

    @Override
    public CompletableFuture<ResponseLog> awaitResponseLog() {
        return delegate.awaitResponseLog();
    }

    @Override
    public String toString() {
        return delegate().toString();
    }
}
