/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.http;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceCodec;
import com.linecorp.armeria.server.ServiceInvocationHandler;
import com.linecorp.armeria.server.SimpleService;

/**
 * A {@link Service} that handles an HTTP request. This {@link Service} must run on a {@link ServerPort}
 * whose {@link SessionProtocol} is {@linkplain SessionProtocol#ofHttp() HTTP}.
 */
public class HttpService implements Service {

    private ServiceCodec codec;
    private final ServiceInvocationHandler handler;

    /**
     * Creates a new instance with the specified {@link ServiceInvocationHandler}.
     */
    public HttpService(ServiceInvocationHandler handler) {
        codec = new HttpServiceCodec(requireNonNull(handler, "handler").getClass().getName());
        this.handler = handler;
    }

    /**
     * Creates a new instance with {@link ServiceInvocationHandler} unspecified. Use this constructor and
     * override the {@link #handler()} method if you cannot instantiate your handler because it requires this
     * {@link Service} to be instantiated first.
     */
    public HttpService() {
        handler = null;
    }

    @Override
    public ServiceCodec codec() {
        ServiceCodec codec = this.codec;
        if (codec == null) {
            return this.codec = new HttpServiceCodec(handler().getClass().getName());
        } else {
            return codec;
        }
    }

    @Override
    public ServiceInvocationHandler handler() {
        final ServiceInvocationHandler handler = this.handler;
        if (handler == null) {
            throw new IllegalStateException(getClass().getName() + ".handler() not implemented");
        }
        return handler;
    }

    @Override
    public String toString() {
        return "HttpService(" + handler().getClass().getSimpleName() + ')';
    }
}
