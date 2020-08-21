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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.AttributeKey;

/**
 * Wraps an existing {@link RequestContext}.
 *
 * @param <T> the self type
 */
public abstract class RequestContextWrapper<T extends RequestContext> implements RequestContext {

    private final T delegate;

    /**
     * Creates a new instance.
     */
    protected RequestContextWrapper(T delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the delegate context.
     */
    protected final T delegate() {
        return delegate;
    }

    @Nullable
    @Override
    public ServiceRequestContext root() {
        return delegate().root();
    }

    @Nullable
    @Override
    public <V> V attr(AttributeKey<V> key) {
        return delegate().attr(key);
    }

    @Nullable
    @Override
    public <V> V ownAttr(AttributeKey<V> key) {
        return delegate().ownAttr(key);
    }

    @Override
    public boolean hasAttr(AttributeKey<?> key) {
        return delegate().hasAttr(key);
    }

    @Override
    public boolean hasOwnAttr(AttributeKey<?> key) {
        return delegate().hasOwnAttr(key);
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        return delegate().attrs();
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> ownAttrs() {
        return delegate().ownAttrs();
    }

    @Override
    public <V> V setAttr(AttributeKey<V> key, @Nullable V value) {
        return delegate().setAttr(key, value);
    }

    @Override
    public HttpRequest request() {
        return delegate().request();
    }

    @Nullable
    @Override
    public RpcRequest rpcRequest() {
        return delegate().rpcRequest();
    }

    @Override
    public void updateRequest(HttpRequest req) {
        delegate().updateRequest(req);
    }

    @Override
    public void updateRpcRequest(RpcRequest rpcReq) {
        delegate().updateRpcRequest(rpcReq);
    }

    @Override
    public SessionProtocol sessionProtocol() {
        return delegate().sessionProtocol();
    }

    @Nullable
    @Override
    public <A extends SocketAddress> A remoteAddress() {
        return delegate().remoteAddress();
    }

    @Nullable
    @Override
    public <A extends SocketAddress> A localAddress() {
        return delegate().localAddress();
    }

    @Nullable
    @Override
    public SSLSession sslSession() {
        return delegate().sslSession();
    }

    @Override
    public RequestId id() {
        return delegate().id();
    }

    @Override
    public HttpMethod method() {
        return delegate().method();
    }

    @Override
    public String path() {
        return delegate().path();
    }

    @Override
    public String decodedPath() {
        return delegate().decodedPath();
    }

    @Override
    public String query() {
        return delegate().query();
    }

    @Override
    public RequestLogAccess log() {
        return delegate().log();
    }

    @Override
    public RequestLogBuilder logBuilder() {
        return delegate().logBuilder();
    }

    @Override
    public MeterRegistry meterRegistry() {
        return delegate().meterRegistry();
    }

    @Override
    public ContextAwareEventLoop eventLoop() {
        return delegate().eventLoop();
    }

    @Override
    public ByteBufAllocator alloc() {
        return delegate().alloc();
    }

    @Override
    public String toString() {
        return delegate().toString();
    }
}
