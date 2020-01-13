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

package com.linecorp.armeria.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AttributeKey;

/**
 * Wraps an existing {@link ClientRequestContext}.
 */
public class ClientRequestContextWrapper
        extends RequestContextWrapper<ClientRequestContext> implements ClientRequestContext {

    /**
     * Creates a new instance.
     */
    protected ClientRequestContextWrapper(ClientRequestContext delegate) {
        super(delegate);
    }

    @Nullable
    @Override
    public ServiceRequestContext root() {
        return delegate().root();
    }

    @Override
    public <V> V ownAttr(AttributeKey<V> key) {
        return delegate().ownAttr(key);
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> ownAttrs() {
        return delegate().ownAttrs();
    }

    @Override
    public ClientRequestContext newDerivedContext(RequestId id, @Nullable HttpRequest req,
                                                  @Nullable RpcRequest rpcReq, Endpoint endpoint) {
        return delegate().newDerivedContext(id, req, rpcReq, endpoint);
    }

    @Override
    public EndpointGroup endpointGroup() {
        return delegate().endpointGroup();
    }

    @Override
    public Endpoint endpoint() {
        return delegate().endpoint();
    }

    @Override
    public String fragment() {
        return delegate().fragment();
    }

    @Override
    public ClientOptions options() {
        return delegate().options();
    }

    @Override
    public long writeTimeoutMillis() {
        return delegate().writeTimeoutMillis();
    }

    @Override
    public void setWriteTimeoutMillis(long writeTimeoutMillis) {
        delegate().setWriteTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public void setWriteTimeout(Duration writeTimeout) {
        delegate().setWriteTimeout(writeTimeout);
    }

    @Override
    public long responseTimeoutMillis() {
        return delegate().responseTimeoutMillis();
    }

    @Override
    public void clearResponseTimeout() {
        delegate().clearResponseTimeout();
    }

    @Override
    public void setResponseTimeoutMillis(long responseTimeoutMillis) {
        delegate().setResponseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public void setResponseTimeout(Duration responseTimeout) {
        delegate().setResponseTimeout(responseTimeout);
    }

    @Override
    public void extendResponseTimeoutMillis(long adjustmentMillis) {
        delegate().extendResponseTimeoutMillis(adjustmentMillis);
    }

    @Override
    public void extendResponseTimeout(Duration adjustment) {
        delegate().extendResponseTimeout(adjustment);
    }

    @Override
    public void setResponseTimeoutAfterMillis(long responseTimeoutMillis) {
        delegate().setResponseTimeoutAfterMillis(responseTimeoutMillis);
    }

    @Override
    public void setResponseTimeoutAfter(Duration responseTimeout) {
        delegate().setResponseTimeoutAfter(responseTimeout);
    }

    @Override
    public void setResponseTimeoutAtMillis(long responseTimeoutAtMillis) {
        delegate().setResponseTimeoutAtMillis(responseTimeoutAtMillis);
    }

    @Override
    public void setResponseTimeoutAt(Instant responseTimeoutAt) {
        delegate().setResponseTimeoutAt(responseTimeoutAt);
    }

    @Override
    @Nullable
    public Runnable responseTimeoutHandler() {
        return delegate().responseTimeoutHandler();
    }

    @Override
    public void setResponseTimeoutHandler(Runnable responseTimeoutHandler) {
       delegate().setResponseTimeoutHandler(responseTimeoutHandler);
    }

    @Override
    public long maxResponseLength() {
        return delegate().maxResponseLength();
    }

    @Override
    public void setMaxResponseLength(long maxResponseLength) {
        delegate().setMaxResponseLength(maxResponseLength);
    }

    @Override
    public HttpHeaders additionalRequestHeaders() {
        return delegate().additionalRequestHeaders();
    }

    @Override
    public void setAdditionalRequestHeader(CharSequence name, Object value) {
        delegate().setAdditionalRequestHeader(name, value);
    }

    @Override
    public void setAdditionalRequestHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        delegate().setAdditionalRequestHeaders(headers);
    }

    @Override
    public void addAdditionalRequestHeader(CharSequence name, Object value) {
        delegate().addAdditionalRequestHeader(name, value);
    }

    @Override
    public void addAdditionalRequestHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        delegate().setAdditionalRequestHeaders(headers);
    }

    @Override
    public boolean removeAdditionalRequestHeader(CharSequence name) {
        return delegate().removeAdditionalRequestHeader(name);
    }
}
