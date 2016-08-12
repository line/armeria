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

package com.linecorp.armeria.client;

import java.time.Duration;

import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.server.ServiceRequestContext;

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
    public Endpoint endpoint() {
        return delegate().endpoint();
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
}
