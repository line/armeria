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

import java.net.InetAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.linecorp.armeria.common.ContextAwareScheduledExecutorService;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.util.TimeoutMode;

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

    @Nonnull
    @Override
    public HttpRequest request() {
        final HttpRequest req = super.request();
        assert req != null;
        return req;
    }

    @Nonnull
    @Override
    public <A extends SocketAddress> A remoteAddress() {
        return delegate().remoteAddress();
    }

    @Nonnull
    @Override
    public <A extends SocketAddress> A localAddress() {
        return delegate().localAddress();
    }

    @Override
    public InetAddress clientAddress() {
        return delegate().clientAddress();
    }

    @Deprecated
    @Override
    public ServiceRequestContext newDerivedContext(RequestId id,
                                                   @Nullable HttpRequest req,
                                                   @Nullable RpcRequest rpcReq) {
        return delegate().newDerivedContext(id, req, rpcReq);
    }

    @Override
    public ServiceConfig config() {
        return delegate().config();
    }

    @Override
    public RoutingContext routingContext() {
        return delegate().routingContext();
    }

    @Override
    public Map<String, String> pathParams() {
        return delegate().pathParams();
    }

    @Override
    public ContextAwareScheduledExecutorService blockingTaskExecutor() {
        return delegate().blockingTaskExecutor();
    }

    @Override
    public String mappedPath() {
        return delegate().mappedPath();
    }

    @Override
    public String decodedMappedPath() {
        return delegate().decodedMappedPath();
    }

    @Nullable
    @Override
    public MediaType negotiatedResponseMediaType() {
        return delegate().negotiatedResponseMediaType();
    }

    @Override
    public long requestTimeoutMillis() {
        return delegate().requestTimeoutMillis();
    }

    @Override
    public void clearRequestTimeout() {
        delegate().clearRequestTimeout();
    }

    @Override
    public void setRequestTimeoutMillis(TimeoutMode mode, long requestTimeoutMillis) {
        delegate().setRequestTimeoutMillis(mode, requestTimeoutMillis);
    }

    @Override
    public void setRequestTimeout(TimeoutMode mode, Duration requestTimeout) {
        delegate().setRequestTimeout(mode, requestTimeout);
    }

    @Nullable
    @Override
    public Runnable requestTimeoutHandler() {
        return delegate().requestTimeoutHandler();
    }

    @Override
    public void setRequestTimeoutHandler(Runnable requestTimeoutHandler) {
        delegate().setRequestTimeoutHandler(requestTimeoutHandler);
    }

    @Override
    public void timeoutNow() {
        delegate().timeoutNow();
    }

    @Override
    public boolean isTimedOut() {
        return delegate().isTimedOut();
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
    public HttpHeaders additionalResponseHeaders() {
        return delegate().additionalResponseHeaders();
    }

    @Override
    public void setAdditionalResponseHeader(CharSequence name, Object value) {
        delegate().setAdditionalResponseHeader(name, value);
    }

    @Override
    public void addAdditionalResponseHeader(CharSequence name, Object value) {
        delegate().addAdditionalResponseHeader(name, value);
    }

    @Override
    public void mutateAdditionalResponseHeaders(Consumer<HttpHeadersBuilder> mutator) {
        delegate().mutateAdditionalResponseHeaders(mutator);
    }

    @Override
    public HttpHeaders additionalResponseTrailers() {
        return delegate().additionalResponseTrailers();
    }

    @Override
    public void setAdditionalResponseTrailer(CharSequence name, Object value) {
        delegate().setAdditionalResponseTrailer(name, value);
    }

    @Override
    public void addAdditionalResponseTrailer(CharSequence name, Object value) {
        delegate().addAdditionalResponseTrailer(name, value);
    }

    @Override
    public void mutateAdditionalResponseTrailers(Consumer<HttpHeadersBuilder> mutator) {
        delegate().mutateAdditionalResponseTrailers(mutator);
    }

    @Override
    public ProxiedAddresses proxiedAddresses() {
        return delegate().proxiedAddresses();
    }
}
