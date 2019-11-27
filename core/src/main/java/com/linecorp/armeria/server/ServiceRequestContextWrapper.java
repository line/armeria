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
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.server.logging.AccessLogWriter;

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

    @Override
    public ServiceRequestContext newDerivedContext(RequestId id,
                                                   @Nullable HttpRequest req,
                                                   @Nullable RpcRequest rpcReq) {
        return delegate().newDerivedContext(id, req, rpcReq);
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
    public Route route() {
        return delegate().route();
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
    public HttpService service() {
        return delegate().service();
    }

    @Override
    public ScheduledExecutorService blockingTaskExecutor() {
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
    public long maxRequestLength() {
        return delegate().maxRequestLength();
    }

    @Override
    public void setMaxRequestLength(long maxRequestLength) {
        delegate().setMaxRequestLength(maxRequestLength);
    }

    @Override
    public boolean verboseResponses() {
        return delegate().verboseResponses();
    }

    @Override
    public AccessLogWriter accessLogWriter() {
        return delegate().accessLogWriter();
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
    public void setAdditionalResponseHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        delegate().setAdditionalResponseHeaders(headers);
    }

    @Override
    public void addAdditionalResponseHeader(CharSequence name, Object value) {
        delegate().addAdditionalResponseHeader(name, value);
    }

    @Override
    public void addAdditionalResponseHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        delegate().addAdditionalResponseHeaders(headers);
    }

    @Override
    public boolean removeAdditionalResponseHeader(CharSequence name) {
        return delegate().removeAdditionalResponseHeader(name);
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
    public void setAdditionalResponseTrailers(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        delegate().setAdditionalResponseTrailers(headers);
    }

    @Override
    public void addAdditionalResponseTrailer(CharSequence name, Object value) {
        delegate().addAdditionalResponseTrailer(name, value);
    }

    @Override
    public void addAdditionalResponseTrailers(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        delegate().addAdditionalResponseTrailers(headers);
    }

    @Override
    public boolean removeAdditionalResponseTrailer(CharSequence name) {
        return delegate().removeAdditionalResponseTrailer(name);
    }

    @Override
    public ProxiedAddresses proxiedAddresses() {
        return delegate().proxiedAddresses();
    }
}
