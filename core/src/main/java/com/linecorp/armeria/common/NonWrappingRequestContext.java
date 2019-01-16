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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.DefaultAttributeMap;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Default {@link RequestContext} implementation.
 */
public abstract class NonWrappingRequestContext extends AbstractRequestContext {

    private final MeterRegistry meterRegistry;
    private final DefaultAttributeMap attrs = new DefaultAttributeMap();
    private final SessionProtocol sessionProtocol;
    private final HttpMethod method;
    private final String path;
    @Nullable
    private String decodedPath;
    @Nullable
    private final String query;
    private final Request request;

    // Callbacks
    @Nullable
    private List<Consumer<? super RequestContext>> onEnterCallbacks;
    @Nullable
    private List<Consumer<? super RequestContext>> onExitCallbacks;
    @Nullable
    private List<BiConsumer<? super RequestContext, ? super RequestContext>> onChildCallbacks;

    /**
     * Creates a new instance.
     *
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param request the request associated with this context
     */
    protected NonWrappingRequestContext(
            MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            HttpMethod method, String path, @Nullable String query, Request request) {

        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        this.sessionProtocol = requireNonNull(sessionProtocol, "sessionProtocol");
        this.method = requireNonNull(method, "method");
        this.path = requireNonNull(path, "path");
        this.query = query;
        this.request = requireNonNull(request, "request");
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
    @SuppressWarnings("unchecked")
    public final <T extends Request> T request() {
        return (T) request;
    }

    @Override
    public final MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        return attrs.attr(key);
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        return attrs.hasAttr(key);
    }

    @Override
    public Iterator<Attribute<?>> attrs() {
        return attrs.attrs();
    }

    @Override
    public final void onEnter(Consumer<? super RequestContext> callback) {
        requireNonNull(callback, "callback");
        if (onEnterCallbacks == null) {
            onEnterCallbacks = new ArrayList<>(4);
        }
        onEnterCallbacks.add(callback);
    }

    @Override
    public final void onExit(Consumer<? super RequestContext> callback) {
        requireNonNull(callback, "callback");
        if (onExitCallbacks == null) {
            onExitCallbacks = new ArrayList<>(4);
        }
        onExitCallbacks.add(callback);
    }

    @Override
    public final void onChild(BiConsumer<? super RequestContext, ? super RequestContext> callback) {
        requireNonNull(callback, "callback");
        if (onChildCallbacks == null) {
            onChildCallbacks = new ArrayList<>(4);
        }
        onChildCallbacks.add(callback);
    }

    @Override
    public void invokeOnEnterCallbacks() {
        invokeCallbacks(onEnterCallbacks);
    }

    @Override
    public void invokeOnExitCallbacks() {
        invokeCallbacks(onExitCallbacks);
    }

    private void invokeCallbacks(@Nullable List<Consumer<? super RequestContext>> callbacks) {
        if (callbacks == null) {
            return;
        }

        for (Consumer<? super RequestContext> callback : callbacks) {
            callback.accept(this);
        }
    }

    @Override
    public void invokeOnChildCallbacks(RequestContext newCtx) {
        final List<BiConsumer<? super RequestContext, ? super RequestContext>> callbacks = onChildCallbacks;
        if (callbacks == null) {
            return;
        }

        for (BiConsumer<? super RequestContext, ? super RequestContext> callback : callbacks) {
            callback.accept(this, newCtx);
        }
    }
}
