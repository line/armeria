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

package com.linecorp.armeria.common;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.internal.DefaultAttributeMap;

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Default {@link RequestContext} implementation.
 */
public abstract class NonWrappingRequestContext extends AbstractRequestContext {

    private final DefaultAttributeMap attrs = new DefaultAttributeMap();
    private final SessionProtocol sessionProtocol;
    private final String method;
    private final String path;
    private final Object request;
    private List<Runnable> onEnterCallbacks;
    private List<Runnable> onExitCallbacks;
    private boolean timedOut;

    /**
     * Creates a new instance.
     *
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param request the request associated with this context
     */
    protected NonWrappingRequestContext(
            SessionProtocol sessionProtocol, String method, String path, Object request) {
        this.sessionProtocol = sessionProtocol;
        this.method = method;
        this.path = path;
        this.request = request;
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

    @Nullable
    @Override
    public SSLSession sslSession() {
        final Channel ch = channel();
        if (ch == null) {
            return null;
        }

        final SslHandler sslHandler = ch.pipeline().get(SslHandler.class);
        return sslHandler != null ? sslHandler.engine().getSession() : null;
    }

    @Override
    public final String method() {
        return method;
    }

    @Override
    public final String path() {
        return path;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final <T> T request() {
        return (T) request;
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
    public final void onEnter(Runnable callback) {
        if (onEnterCallbacks == null) {
            onEnterCallbacks = new ArrayList<>(4);
        }
        onEnterCallbacks.add(callback);
    }

    @Override
    public final void onExit(Runnable callback) {
        if (onExitCallbacks == null) {
            onExitCallbacks = new ArrayList<>(4);
        }
        onExitCallbacks.add(callback);
    }

    @Override
    public void invokeOnEnterCallbacks() {
        final List<Runnable> onEnterCallbacks = this.onEnterCallbacks;
        if (onEnterCallbacks != null) {
            onEnterCallbacks.forEach(Runnable::run);
        }
    }

    @Override
    public void invokeOnExitCallbacks() {
        final List<Runnable> onExitCallbacks = this.onExitCallbacks;
        if (onExitCallbacks != null) {
            onExitCallbacks.forEach(Runnable::run);
        }
    }
}
