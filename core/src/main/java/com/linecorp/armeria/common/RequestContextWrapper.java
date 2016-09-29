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

import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;

import io.netty.channel.EventLoop;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Wraps an existing {@link RequestContext}.
 *
 * @param <T> the self type
 */
public abstract class RequestContextWrapper<T extends RequestContext> extends AbstractRequestContext {

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
    public SessionProtocol sessionProtocol() {
        return delegate().sessionProtocol();
    }

    @Override
    public String method() {
        return delegate().method();
    }

    @Override
    public String path() {
        return delegate().path();
    }

    @Override
    public <T> T request() {
        return delegate().request();
    }

    @Override
    public RequestLogBuilder requestLogBuilder() {
        return delegate().requestLogBuilder();
    }

    @Override
    public ResponseLogBuilder responseLogBuilder() {
        return delegate().responseLogBuilder();
    }

    @Override
    public CompletableFuture<RequestLog> requestLogFuture() {
        return delegate().requestLogFuture();
    }

    @Override
    public CompletableFuture<ResponseLog> responseLogFuture() {
        return delegate().responseLogFuture();
    }

    @Override
    public Iterator<Attribute<?>> attrs() {
        return delegate().attrs();
    }

    @Override
    public EventLoop eventLoop() {
        return delegate().eventLoop();
    }

    @Override
    public void onEnter(Runnable callback) {
        delegate().onEnter(callback);
    }

    @Override
    public void onExit(Runnable callback) {
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

    @Override
    public <V> Attribute<V> attr(AttributeKey<V> key) {
        return delegate().attr(key);
    }

    @Override
    public <V> boolean hasAttr(AttributeKey<V> key) {
        return delegate().hasAttr(key);
    }
}
