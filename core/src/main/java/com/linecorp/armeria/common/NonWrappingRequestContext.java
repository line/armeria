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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.UnstableApi;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * A skeletal {@link RequestContext} implementation that helps to implement a non-wrapping
 * {@link RequestContext}.
 */
@UnstableApi
public abstract class NonWrappingRequestContext implements RequestContext {

    private final MeterRegistry meterRegistry;
    private final DefaultAttributeMap attrs;
    private final SessionProtocol sessionProtocol;
    private final RequestId id;
    private final HttpMethod method;
    private final String path;
    @Nullable
    private String decodedPath;
    @Nullable
    private final String query;
    @Nullable
    private volatile HttpRequest req;
    @Nullable
    private volatile RpcRequest rpcReq;

    /**
     * Creates a new instance.
     *
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param id the {@link RequestId} associated with this context
     * @param req the {@link HttpRequest} associated with this context
     * @param rpcReq the {@link RpcRequest} associated with this context
     */
    protected NonWrappingRequestContext(
            MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, HttpMethod method, String path, @Nullable String query,
            @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
            @Nullable RequestContext rootAttributeMap) {

        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        attrs = new DefaultAttributeMap(rootAttributeMap);
        this.sessionProtocol = requireNonNull(sessionProtocol, "sessionProtocol");
        this.id = requireNonNull(id, "id");
        this.method = requireNonNull(method, "method");
        this.path = requireNonNull(path, "path");
        this.query = query;
        this.req = req;
        this.rpcReq = rpcReq;
    }

    @Override
    public HttpRequest request() {
        return req;
    }

    @Override
    public RpcRequest rpcRequest() {
        return rpcReq;
    }

    @Override
    public final void updateRequest(HttpRequest req) {
        requireNonNull(req, "req");
        validateHeaders(req.headers());
        unsafeUpdateRequest(req);
    }

    @Override
    public final void updateRpcRequest(RpcRequest rpcReq) {
        requireNonNull(rpcReq, "rpcReq");
        this.rpcReq = rpcReq;
    }

    /**
     * Validates the specified {@link RequestHeaders}. By default, this method will raise
     * an {@link IllegalArgumentException} if it does not have {@code ":scheme"} or {@code ":authority"}
     * header.
     */
    protected void validateHeaders(RequestHeaders headers) {
        checkArgument(headers.scheme() != null && headers.authority() != null,
                      "must set ':scheme' and ':authority' headers");
    }

    /**
     * Replaces the {@link HttpRequest} associated with this context with the specified one
     * without any validation. Internal use only. Use it at your own risk.
     */
    protected final void unsafeUpdateRequest(HttpRequest req) {
        this.req = req;
    }

    @Override
    public final SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    /**
     * Returns the {@link Channel} that is handling this request, or {@code null} if the connection is not
     * established yet.
     */
    @Nullable
    protected abstract Channel channel();

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <A extends SocketAddress> A remoteAddress() {
        final Channel ch = channel();
        return ch != null ? (A) ch.remoteAddress() : null;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <A extends SocketAddress> A localAddress() {
        final Channel ch = channel();
        return ch != null ? (A) ch.localAddress() : null;
    }

    @Override
    public final RequestId id() {
        return id;
    }

    @Override
    public final HttpMethod method() {
        return method;
    }

    @Override
    public final String path() {
        return path;
    }

    @Override
    public final String decodedPath() {
        final String decodedPath = this.decodedPath;
        if (decodedPath != null) {
            return decodedPath;
        }

        return this.decodedPath = ArmeriaHttpUtil.decodePath(path);
    }

    @Override
    public final String query() {
        return query;
    }

    @Override
    public final MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    @Nullable
    @Override
    public <V> V attr(AttributeKey<V> key) {
        requireNonNull(key, "key");
        return attrs.attr(key);
    }

    /**
     * Returns the value mapped to the given {@link AttributeKey} or {@code null} if there's no value set by
     * {@link #setAttr(AttributeKey, Object)} or {@link #setAttrIfAbsent(AttributeKey, Object)}.
     * Unlike {@link #attr(AttributeKey)}, this does not search in {@code rootAttributeMap}.
     *
     * @see #attr(AttributeKey)
     */
    @Nullable
    public <V> V ownAttr(AttributeKey<V> key) {
        requireNonNull(key, "key");
        return attrs.ownAttr(key);
    }

    @Override
    public <V> void setAttr(AttributeKey<V> key, @Nullable V value) {
        requireNonNull(key, "key");
        attrs.setAttr(key, value);
    }

    @Nullable
    @Override
    public <V> V setAttrIfAbsent(AttributeKey<V> key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        return attrs.setAttrIfAbsent(key, value);
    }

    @Nullable
    @Override
    public <V> V computeAttrIfAbsent(AttributeKey<V> key,
                                     Function<? super AttributeKey<V>, ? extends V> mappingFunction) {
        requireNonNull(key, "key");
        requireNonNull(mappingFunction, "mappingFunction");
        return attrs.computeAttrIfAbsent(key, mappingFunction);
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        return attrs.attrs();
    }

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this context contains.
     * Unlike {@link #attrs()}, this does not iterate {@code rootAttributeMap}.
     *
     * @see #attrs()
     */
    public Iterator<Entry<AttributeKey<?>, Object>> ownAttrs() {
        return attrs.ownAttrs();
    }
}
