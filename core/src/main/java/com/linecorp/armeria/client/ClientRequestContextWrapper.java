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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.util.TimeoutMode;

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
                                                  @Nullable RpcRequest rpcReq, @Nullable Endpoint endpoint) {
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
    public void setResponseTimeoutMillis(TimeoutMode mode, long responseTimeoutMillis) {
        delegate().setResponseTimeoutMillis(mode, responseTimeoutMillis);
    }

    @Override
    public void setResponseTimeout(TimeoutMode mode, Duration responseTimeout) {
        delegate().setResponseTimeout(mode, responseTimeout);
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
    public void addAdditionalRequestHeader(CharSequence name, Object value) {
        delegate().addAdditionalRequestHeader(name, value);
    }

    @Override
    public void mutateAdditionalRequestHeaders(Consumer<HttpHeadersBuilder> mutator) {
        delegate().additionalRequestHeaders();
    }

    @Override
    public void cancel(Throwable cause) {
        delegate().cancel(cause);
    }

    @Override
    public void cancel() {
        delegate().cancel();
    }

    @Override
    public void timeoutNow() {
        delegate().timeoutNow();
    }

    @Override
    public Throwable cancellationCause() {
        return delegate().cancellationCause();
    }

    @Override
    public boolean isCancelled() {
        return delegate().isCancelled();
    }

    @Override
    public boolean isTimedOut() {
        return delegate().isTimedOut();
    }

    @Override
    public CompletableFuture<Void> whenResponseTimingOut() {
        return delegate().whenResponseTimingOut();
    }

    @Override
    public CompletableFuture<Void> whenResponseTimedOut() {
        return delegate().whenResponseTimedOut();
    }
}
