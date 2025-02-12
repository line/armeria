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

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
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
        return unwrap().newDerivedContext(id, req, rpcReq, endpoint);
    }

    @Override
    public EndpointGroup endpointGroup() {
        return unwrap().endpointGroup();
    }

    @Nullable
    @Override
    public Endpoint endpoint() {
        return unwrap().endpoint();
    }

    @Nullable
    @Override
    public String fragment() {
        return unwrap().fragment();
    }

    @Nullable
    @Override
    public String authority() {
        return unwrap().authority();
    }

    @Nullable
    @Override
    public String host() {
        return unwrap().host();
    }

    @Override
    public URI uri() {
        return unwrap().uri();
    }

    @Override
    public ClientOptions options() {
        return unwrap().options();
    }

    @Override
    public long writeTimeoutMillis() {
        return unwrap().writeTimeoutMillis();
    }

    @Override
    public void setWriteTimeoutMillis(long writeTimeoutMillis) {
        unwrap().setWriteTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public void setWriteTimeout(Duration writeTimeout) {
        unwrap().setWriteTimeout(writeTimeout);
    }

    @Override
    public long responseTimeoutMillis() {
        return unwrap().responseTimeoutMillis();
    }

    @Override
    public void clearResponseTimeout() {
        unwrap().clearResponseTimeout();
    }

    @Override
    public void setResponseTimeoutMillis(TimeoutMode mode, long responseTimeoutMillis) {
        unwrap().setResponseTimeoutMillis(mode, responseTimeoutMillis);
    }

    @Override
    public void setResponseTimeout(TimeoutMode mode, Duration responseTimeout) {
        unwrap().setResponseTimeout(mode, responseTimeout);
    }

    @Override
    public long maxResponseLength() {
        return unwrap().maxResponseLength();
    }

    @Override
    public void setMaxResponseLength(long maxResponseLength) {
        unwrap().setMaxResponseLength(maxResponseLength);
    }

    @Override
    public HttpHeaders defaultRequestHeaders() {
        return unwrap().defaultRequestHeaders();
    }

    @Override
    public HttpHeaders additionalRequestHeaders() {
        return unwrap().additionalRequestHeaders();
    }

    @Override
    public void setAdditionalRequestHeader(CharSequence name, Object value) {
        unwrap().setAdditionalRequestHeader(name, value);
    }

    @Override
    public void addAdditionalRequestHeader(CharSequence name, Object value) {
        unwrap().addAdditionalRequestHeader(name, value);
    }

    @Override
    public void mutateAdditionalRequestHeaders(Consumer<HttpHeadersBuilder> mutator) {
        unwrap().additionalRequestHeaders();
    }

    @Override
    public ExchangeType exchangeType() {
        return unwrap().exchangeType();
    }

    @Override
    public ResponseTimeoutMode responseTimeoutMode() {
        return unwrap().responseTimeoutMode();
    }

    @Override
    public void hook(Supplier<? extends AutoCloseable> contextHook) {
        unwrap().hook(contextHook);
    }

    @Override
    public Supplier<AutoCloseable> hook() {
        return unwrap().hook();
    }

    @Override
    public boolean isCancelled() {
        return unwrap().isCancelled();
    }

    @Override
    public boolean isTimedOut() {
        return unwrap().isTimedOut();
    }

    @Override
    public CompletableFuture<Throwable> whenResponseCancelling() {
        return unwrap().whenResponseCancelling();
    }

    @Override
    public CompletableFuture<Throwable> whenResponseCancelled() {
        return unwrap().whenResponseCancelled();
    }

    @Deprecated
    @Override
    public CompletableFuture<Void> whenResponseTimingOut() {
        return unwrap().whenResponseTimingOut();
    }

    @Deprecated
    @Override
    public CompletableFuture<Void> whenResponseTimedOut() {
        return unwrap().whenResponseTimedOut();
    }

    @Override
    public ClientRequestContext unwrapAll() {
        return (ClientRequestContext) super.unwrapAll();
    }
}
