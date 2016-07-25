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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

/**
 * A simple {@link Service}. Prefer {@link Service#of(ServiceCodec, ServiceInvocationHandler)} unless you want
 * to define a new dedicated {@link Service} type by extending this class
 */
public class SimpleService implements Service {

    private final ServiceCodec codec;
    private final ServiceInvocationHandler handler;

    /**
     * Creates a new instance with the specified {@link ServiceCodec} and {@link ServiceInvocationHandler}.
     */
    protected SimpleService(ServiceCodec codec, ServiceInvocationHandler handler) {
        this.codec = requireNonNull(codec, "codec");
        this.handler = requireNonNull(handler, "handler");
    }

    @Override
    public final ServiceCodec codec() {
        return codec;
    }

    @Override
    public final ServiceInvocationHandler handler() {
        return handler;
    }

    @Override
    public String toString() {
        return "Service(" + codec().getClass().getSimpleName() + ", " + handler().getClass().getSimpleName() + ')';
    }
}
