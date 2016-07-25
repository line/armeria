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

package com.linecorp.armeria.server.tracing;

import java.util.function.Function;

import com.github.kristofa.brave.Brave;

import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.Service;

/**
 * A {@link Service} decorator that traces HTTP-based service invocations.
 * <p>
 * This decorator retrieves trace data from HTTP headers. The specifications of header names and its values
 * correspond to <a href="http://zipkin.io/">Zipkin</a>.
 */
public class HttpTracingService extends DecoratingService {

    /**
     * Creates a new tracing {@link Service} decorator using the specified {@link Brave} instance.
     */
    public static Function<Service, Service> newDecorator(Brave brave) {
        return service -> new HttpTracingService(service, brave);
    }

    HttpTracingService(Service service, Brave brave) {
        super(service, Function.identity(), handler -> new HttpTracingServiceInvocationHandler(handler, brave));
    }
}