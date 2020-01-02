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
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;
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
    public RequestLog log() {
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
    public EventLoop eventLoop() {
        return delegate().eventLoop();
    }

    @Override
    public void onEnter(Consumer<? super RequestContext> callback) {
        delegate().onEnter(callback);
    }

    @Override
    public void onExit(Consumer<? super RequestContext> callback) {
        delegate().onExit(callback);
    }

    @Override
    public void invokeOnEnterCallbacks() {
        delegate().invokeOnEnterCallbacks();
    }

    @Override
    public void invokeOnExitCallbacks() {
        delegate().invokeOnExitCallbacks();
    }

    @Nullable
    @Override
    public <V> V attr(AttributeKey<V> key) {
        return delegate().attr(key);
    }

    @Override
    public <V> void setAttr(AttributeKey<V> key, @Nullable V value) {
        delegate().setAttr(key, value);
    }

    @Nullable
    @Override
    public <V> V setAttrIfAbsent(AttributeKey<V> key, V value) {
        return delegate().setAttrIfAbsent(key, value);
    }

    @Nullable
    @Override
    public <V> V computeAttrIfAbsent(
            AttributeKey<V> key, Function<? super AttributeKey<V>, ? extends V> mappingFunction) {
        return delegate().computeAttrIfAbsent(key, mappingFunction);
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        return delegate().attrs();
    }

    @Override
    public ByteBufAllocator alloc() {
        return delegate().alloc();
    }
}
