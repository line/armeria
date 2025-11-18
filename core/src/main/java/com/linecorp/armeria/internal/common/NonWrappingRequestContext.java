/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.internal.common.RequestContextUtil.NOOP_CONTEXT_HOOK;
import static com.linecorp.armeria.internal.common.RequestContextUtil.mergeHooks;
import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import com.linecorp.armeria.common.AttributesGetters;
import com.linecorp.armeria.common.ConcurrentAttributes;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RequestTargetForm;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * A skeletal {@link RequestContext} implementation that helps to implement a non-wrapping
 * {@link RequestContext}.
 */
public abstract class NonWrappingRequestContext implements RequestContextExtension {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<NonWrappingRequestContext, Supplier>
            contextHookUpdater = AtomicReferenceFieldUpdater.newUpdater(
            NonWrappingRequestContext.class, Supplier.class, "contextHook");

    private final MeterRegistry meterRegistry;
    private final ConcurrentAttributes attrs;
    private final RequestId id;
    private final HttpMethod method;
    private RequestTarget reqTarget;
    private final ExchangeType exchangeType;
    private long requestAutoAbortDelayMillis;

    @Nullable
    private String decodedPath;

    private final Request originalRequest;
    @Nullable
    private volatile HttpRequest req;
    @Nullable
    private volatile RpcRequest rpcReq;
    // Updated via `contextHookUpdater`
    private volatile Supplier<AutoCloseable> contextHook;

    /**
     * Creates a new instance.
     */
    protected NonWrappingRequestContext(
            MeterRegistry meterRegistry, RequestId id, HttpMethod method, RequestTarget reqTarget,
            ExchangeType exchangeType, long requestAutoAbortDelayMillis,
            @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
            @Nullable AttributesGetters rootAttributeMap, Supplier<? extends AutoCloseable> contextHook) {
        assert req != null || rpcReq != null;

        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        if (rootAttributeMap == null) {
            attrs = ConcurrentAttributes.of();
        } else {
            attrs = ConcurrentAttributes.fromParent(rootAttributeMap);
        }

        this.id = requireNonNull(id, "id");
        this.method = requireNonNull(method, "method");
        this.reqTarget = requireNonNull(reqTarget, "reqTarget");
        this.exchangeType = requireNonNull(exchangeType, "exchangeType");
        this.requestAutoAbortDelayMillis = requestAutoAbortDelayMillis;
        originalRequest = firstNonNull(req, rpcReq);
        this.req = req;
        this.rpcReq = rpcReq;
        //noinspection unchecked
        this.contextHook = (Supplier<AutoCloseable>) contextHook;
    }

    @Nullable
    @Override
    public final HttpRequest request() {
        return req;
    }

    @Nullable
    @Override
    public final RpcRequest rpcRequest() {
        return rpcReq;
    }

    @Override
    public final void updateRequest(HttpRequest req) {
        requireNonNull(req, "req");
        final RequestHeaders headers = req.headers();
        final RequestTarget reqTarget = validateHeaders(headers);

        if (reqTarget == null) {
            throw new IllegalArgumentException("invalid path: " + headers.path());
        }
        if (reqTarget.form() == RequestTargetForm.ABSOLUTE) {
            throw new IllegalArgumentException("invalid path: " + headers.path() +
                                               " (must not contain scheme or authority)");
        }

        this.req = req;
        this.reqTarget = reqTarget;
        decodedPath = null;
    }

    @Override
    public final void updateRpcRequest(RpcRequest rpcReq) {
        requireNonNull(rpcReq, "rpcReq");
        this.rpcReq = rpcReq;
    }

    /**
     * Validates the specified {@link RequestHeaders} and returns the {@link RequestTarget}
     * returned by {@link RequestTarget#forClient(String)} or {@link RequestTarget#forServer(String)}.
     */
    @Nullable
    protected abstract RequestTarget validateHeaders(RequestHeaders headers);

    /**
     * Returns the {@link Channel} that is handling this request, or {@code null} if the connection is not
     * established yet.
     */
    @Nullable
    protected abstract Channel channel();

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
        return reqTarget.path();
    }

    protected final RequestTarget requestTarget() {
        return reqTarget;
    }

    @Override
    public final String decodedPath() {
        final String decodedPath = this.decodedPath;
        if (decodedPath != null) {
            return decodedPath;
        }

        return this.decodedPath = ArmeriaHttpUtil.decodePath(path());
    }

    @Nullable
    @Override
    public final String query() {
        return reqTarget.query();
    }

    @Override
    public ExchangeType exchangeType() {
        return exchangeType;
    }

    @Override
    public final MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    @Override
    public long requestAutoAbortDelayMillis() {
        return requestAutoAbortDelayMillis;
    }

    @Override
    public void setRequestAutoAbortDelayMillis(long delayMillis) {
        requestAutoAbortDelayMillis = delayMillis;
    }

    @Nullable
    @Override
    public <V> V attr(AttributeKey<V> key) {
        requireNonNull(key, "key");
        return attrs.attr(key);
    }

    @Nullable
    @Override
    public final <V> V ownAttr(AttributeKey<V> key) {
        requireNonNull(key, "key");
        return attrs.ownAttr(key);
    }

    @Nullable
    @Override
    public final <V> V setAttr(AttributeKey<V> key, @Nullable V value) {
        requireNonNull(key, "key");
        return attrs.getAndSet(key, value);
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        // TODO(ikhoon): Make this method return `AttributesGetters` in Armeria 2.x
        return attrs.attrs();
    }

    @Override
    public final Iterator<Entry<AttributeKey<?>, Object>> ownAttrs() {
        return attrs.ownAttrs();
    }

    @Override
    @UnstableApi
    public final AttributesGetters attributes() {
        return attrs;
    }

    @Override
    public Request originalRequest() {
        return originalRequest;
    }

    /**
     * Adds a hook which is invoked whenever this {@link NonWrappingRequestContext} is pushed to the
     * {@link RequestContextStorage}. The {@link AutoCloseable} returned by {@code contextHook} will be called
     * whenever this {@link RequestContext} is popped from the {@link RequestContextStorage}.
     * This method is useful when you need to propagate a custom context in this {@link RequestContext}'s scope.
     *
     * <p>Note that this operation is highly performance-sensitive operation, and thus
     * it's not a good idea to run a time-consuming task.
     */
    @UnstableApi
    @Override
    public void hook(Supplier<? extends AutoCloseable> contextHook) {
        requireNonNull(contextHook, "contextHook");

        if (contextHook == NOOP_CONTEXT_HOOK) {
            return;
        }

        for (;;) {
            final Supplier<? extends AutoCloseable> oldContextHook = this.contextHook;
            final Supplier<? extends AutoCloseable> newContextHook = mergeHooks(oldContextHook, contextHook);
            if (contextHookUpdater.compareAndSet(this, oldContextHook, newContextHook)) {
                break;
            }
        }
    }

    @Override
    public Supplier<AutoCloseable> hook() {
        return contextHook;
    }
}
