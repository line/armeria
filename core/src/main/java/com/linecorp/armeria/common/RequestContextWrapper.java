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

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.AttributeKey;

/**
 * Wraps an existing {@link RequestContext}.
 *
 * @param <T> the self type
 */
public abstract class RequestContextWrapper<T extends RequestContext>
        extends AbstractUnwrappable<T> implements RequestContext {
    /**
     * Creates a new instance.
     */
    protected RequestContextWrapper(T delegate) {
        super(requireNonNull(delegate, "delegate"));
    }

    /**
     * Returns the delegate context.
     * @deprecated Use {@link RequestContextWrapper#unwrap()} instead.
     */
    @Deprecated
    protected final T delegate() {
        return unwrap();
    }

    @Nullable
    @Override
    public ServiceRequestContext root() {
        return unwrap().root();
    }

    @Nullable
    @Override
    public <V> V attr(AttributeKey<V> key) {
        return unwrap().attr(key);
    }

    @Nullable
    @Override
    public <V> V ownAttr(AttributeKey<V> key) {
        return unwrap().ownAttr(key);
    }

    @Override
    public boolean hasAttr(AttributeKey<?> key) {
        return unwrap().hasAttr(key);
    }

    @Override
    public boolean hasOwnAttr(AttributeKey<?> key) {
        return unwrap().hasOwnAttr(key);
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        return unwrap().attrs();
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> ownAttrs() {
        return unwrap().ownAttrs();
    }

    @Nullable
    @Override
    public <V> V setAttr(AttributeKey<V> key, @Nullable V value) {
        return unwrap().setAttr(key, value);
    }

    @Nullable
    @Override
    public HttpRequest request() {
        return unwrap().request();
    }

    @Nullable
    @Override
    public RpcRequest rpcRequest() {
        return unwrap().rpcRequest();
    }

    @Override
    public void updateRequest(HttpRequest req) {
        unwrap().updateRequest(req);
    }

    @Override
    public void updateRpcRequest(RpcRequest rpcReq) {
        unwrap().updateRpcRequest(rpcReq);
    }

    @Override
    public SessionProtocol sessionProtocol() {
        return unwrap().sessionProtocol();
    }

    @Nullable
    @Override
    public InetSocketAddress remoteAddress() {
        return unwrap().remoteAddress();
    }

    @Nullable
    @Override
    public InetSocketAddress localAddress() {
        return unwrap().localAddress();
    }

    @Nullable
    @Override
    public SSLSession sslSession() {
        return unwrap().sslSession();
    }

    @Override
    public RequestId id() {
        return unwrap().id();
    }

    @Override
    public HttpMethod method() {
        return unwrap().method();
    }

    @Override
    public String path() {
        return unwrap().path();
    }

    @Override
    public String decodedPath() {
        return unwrap().decodedPath();
    }

    @Nullable
    @Override
    public String query() {
        return unwrap().query();
    }

    @Override
    public URI uri() {
        return unwrap().uri();
    }

    @Override
    public RequestLogAccess log() {
        return unwrap().log();
    }

    @Override
    public RequestLogBuilder logBuilder() {
        return unwrap().logBuilder();
    }

    @Override
    public MeterRegistry meterRegistry() {
        return unwrap().meterRegistry();
    }

    @Override
    public long requestAutoAbortDelayMillis() {
        return unwrap().requestAutoAbortDelayMillis();
    }

    @Override
    public void setRequestAutoAbortDelay(Duration delay) {
        unwrap().setRequestAutoAbortDelay(delay);
    }

    @Override
    public void setRequestAutoAbortDelayMillis(long delayMillis) {
        unwrap().setRequestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public void cancel(Throwable cause) {
        unwrap().cancel(cause);
    }

    @Override
    public void cancel() {
        unwrap().cancel();
    }

    @Override
    public void timeoutNow() {
        unwrap().timeoutNow();
    }

    @Override
    public RequestContext unwrapAll() {
        return (RequestContext) super.unwrapAll();
    }

    @Override
    @Nullable
    public Throwable cancellationCause() {
        return unwrap().cancellationCause();
    }

    @Override
    public ContextAwareEventLoop eventLoop() {
        return unwrap().eventLoop();
    }

    @Override
    public ByteBufAllocator alloc() {
        return unwrap().alloc();
    }

    @Override
    public ExchangeType exchangeType() {
        return unwrap().exchangeType();
    }

    @Override
    public CompletableFuture<Void> initiateConnectionShutdown() {
        return unwrap().initiateConnectionShutdown();
    }

    @Override
    public String toString() {
        return unwrap().toString();
    }
}
