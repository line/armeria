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

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.handler.codec.Headers;
import io.netty.util.AsciiString;

/**
 * Wraps an existing {@link ServiceRequestContext}.
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
    public ClientRequestContext newDerivedContext() {
        return delegate().newDerivedContext();
    }

    @Override
    public ClientRequestContext newDerivedContext(Request request) {
        return delegate().newDerivedContext(request);
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
    public void setAdditionalRequestHeader(AsciiString name, String value) {
        delegate().setAdditionalRequestHeader(name, value);
    }

    @Override
    public void setAdditionalRequestHeaders(Headers<? extends AsciiString, ? extends String, ?> headers) {
        delegate().setAdditionalRequestHeaders(headers);
    }

    @Override
    public void addAdditionalRequestHeader(AsciiString name, String value) {
        delegate().addAdditionalRequestHeader(name, value);
    }

    @Override
    public void addAdditionalRequestHeaders(Headers<? extends AsciiString, ? extends String, ?> headers) {
        delegate().setAdditionalRequestHeaders(headers);
    }

    @Override
    public boolean removeAdditionalRequestHeader(AsciiString name) {
        return delegate().removeAdditionalRequestHeader(name);
    }
}
