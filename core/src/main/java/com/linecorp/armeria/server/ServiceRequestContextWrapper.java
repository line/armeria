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

package com.linecorp.armeria.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import com.linecorp.armeria.common.ContextAwareBlockingTaskExecutor;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TimeoutMode;

/**
 * Wraps an existing {@link ServiceRequestContext}.
 */
public class ServiceRequestContextWrapper
        extends RequestContextWrapper<ServiceRequestContext> implements ServiceRequestContext {

    /**
     * Creates a new instance.
     */
    protected ServiceRequestContextWrapper(ServiceRequestContext delegate) {
        super(delegate);
    }

    @Override
    public ServiceRequestContext root() {
        return unwrap().root();
    }

    @Nonnull
    @Override
    public HttpRequest request() {
        final HttpRequest req = super.request();
        assert req != null;
        return req;
    }

    @Nonnull
    @Override
    public InetSocketAddress remoteAddress() {
        return unwrap().remoteAddress();
    }

    @Nonnull
    @Override
    public InetSocketAddress localAddress() {
        return unwrap().localAddress();
    }

    @Override
    public InetAddress clientAddress() {
        return unwrap().clientAddress();
    }

    @Override
    public ServiceConfig config() {
        return unwrap().config();
    }

    @Nullable
    @Override
    public <T extends HttpService> T findService(Class<? extends T> serviceClass) {
        return unwrap().findService(serviceClass);
    }

    @Override
    public RoutingContext routingContext() {
        return unwrap().routingContext();
    }

    @Override
    public Map<String, String> pathParams() {
        return unwrap().pathParams();
    }

    @Override
    public QueryParams queryParams() {
        return unwrap().queryParams();
    }

    @Override
    public ContextAwareBlockingTaskExecutor blockingTaskExecutor() {
        return unwrap().blockingTaskExecutor();
    }

    @Override
    public String mappedPath() {
        return unwrap().mappedPath();
    }

    @Override
    public String decodedMappedPath() {
        return unwrap().decodedMappedPath();
    }

    @Nullable
    @Override
    public MediaType negotiatedResponseMediaType() {
        return unwrap().negotiatedResponseMediaType();
    }

    @Override
    public long requestTimeoutMillis() {
        return unwrap().requestTimeoutMillis();
    }

    @Override
    public void clearRequestTimeout() {
        unwrap().clearRequestTimeout();
    }

    @Override
    public void setRequestTimeoutMillis(TimeoutMode mode, long requestTimeoutMillis) {
        unwrap().setRequestTimeoutMillis(mode, requestTimeoutMillis);
    }

    @Override
    public void setRequestTimeout(TimeoutMode mode, Duration requestTimeout) {
        unwrap().setRequestTimeout(mode, requestTimeout);
    }

    @Override
    public CompletableFuture<Throwable> whenRequestCancelling() {
        return unwrap().whenRequestCancelling();
    }

    @Override
    public CompletableFuture<Throwable> whenRequestCancelled() {
        return unwrap().whenRequestCancelled();
    }

    @Deprecated
    @Override
    public CompletableFuture<Void> whenRequestTimingOut() {
        return unwrap().whenRequestTimingOut();
    }

    @Deprecated
    @Override
    public CompletableFuture<Void> whenRequestTimedOut() {
        return unwrap().whenRequestTimedOut();
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
    public ExchangeType exchangeType() {
        return unwrap().exchangeType();
    }

    @Override
    public long maxRequestLength() {
        return unwrap().maxRequestLength();
    }

    @Override
    public void setMaxRequestLength(long maxRequestLength) {
        unwrap().setMaxRequestLength(maxRequestLength);
    }

    @Override
    public HttpHeaders additionalResponseHeaders() {
        return unwrap().additionalResponseHeaders();
    }

    @Override
    public void setAdditionalResponseHeader(CharSequence name, Object value) {
        unwrap().setAdditionalResponseHeader(name, value);
    }

    @Override
    public void addAdditionalResponseHeader(CharSequence name, Object value) {
        unwrap().addAdditionalResponseHeader(name, value);
    }

    @Override
    public void mutateAdditionalResponseHeaders(Consumer<HttpHeadersBuilder> mutator) {
        unwrap().mutateAdditionalResponseHeaders(mutator);
    }

    @Override
    public HttpHeaders additionalResponseTrailers() {
        return unwrap().additionalResponseTrailers();
    }

    @Override
    public void setAdditionalResponseTrailer(CharSequence name, Object value) {
        unwrap().setAdditionalResponseTrailer(name, value);
    }

    @Override
    public void addAdditionalResponseTrailer(CharSequence name, Object value) {
        unwrap().addAdditionalResponseTrailer(name, value);
    }

    @Override
    public void mutateAdditionalResponseTrailers(Consumer<HttpHeadersBuilder> mutator) {
        unwrap().mutateAdditionalResponseTrailers(mutator);
    }

    @Override
    public ProxiedAddresses proxiedAddresses() {
        return unwrap().proxiedAddresses();
    }

    @Override
    public boolean shouldReportUnhandledExceptions() {
        return unwrap().shouldReportUnhandledExceptions();
    }

    @Override
    public void setShouldReportUnhandledExceptions(boolean value) {
        unwrap().setShouldReportUnhandledExceptions(value);
    }

    @Override
    public boolean shouldReportUnloggedExceptions() {
        return unwrap().shouldReportUnloggedExceptions();
    }

    @Override
    public void setShouldReportUnloggedExceptions(boolean value) {
        unwrap().setShouldReportUnloggedExceptions(value);
    }

    @Override
    public CompletableFuture<Void> initiateConnectionShutdown(long drainDurationMicros) {
        return unwrap().initiateConnectionShutdown(drainDurationMicros);
    }

    @Override
    public CompletableFuture<Void> initiateConnectionShutdown() {
        return unwrap().initiateConnectionShutdown();
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
    public ServiceRequestContext unwrapAll() {
        return (ServiceRequestContext) super.unwrapAll();
    }
}
