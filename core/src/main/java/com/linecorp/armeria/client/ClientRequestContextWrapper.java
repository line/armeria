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
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RpcRequest;

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

    @Override
    public ClientRequestContext newDerivedContext(RequestId id, @Nullable HttpRequest req,
                                                  @Nullable RpcRequest rpcReq, Endpoint endpoint) {
        return delegate().newDerivedContext(id, req, rpcReq, endpoint);
    }

    @Override
    public EndpointSelector endpointSelector() {
        return delegate().endpointSelector();
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
    public void setResponseTimeoutMillis(long responseTimeoutMillis) {
        delegate().setResponseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public void setResponseTimeout(Duration responseTimeout) {
        delegate().setResponseTimeout(responseTimeout);
    }

    @Override
    public void adjustResponseTimeoutMillis(long adjustmentMillis) {
        delegate().adjustResponseTimeoutMillis(adjustmentMillis);
    }

    @Override
    public void adjustResponseTimeout(Duration adjustment) {
        delegate().adjustResponseTimeout(adjustment);
    }

    @Override
    public void resetResponseTimeoutMillis(long responseTimeoutMillis) {
        delegate().resetResponseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public void resetResponseTimeout(Duration responseTimeout) {
        delegate().resetResponseTimeout(responseTimeout);
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
