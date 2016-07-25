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

package com.linecorp.armeria.client.tracing;

import java.util.function.Function;

import com.github.kristofa.brave.Brave;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.DecoratingClient;

/**
 * A {@link Client} decorator that traces HTTP-based remote service invocations.
 * <p>
 * This decorator puts trace data into HTTP headers. The specifications of header names and its values
 * correspond to <a href="http://zipkin.io/">Zipkin</a>.
 */
public class HttpTracingClient extends DecoratingClient {

    /**
     * Creates a new tracing {@link Client} decorator using the specified {@link Brave} instance.
     */
    public static Function<Client, Client> newDecorator(Brave brave) {
        return client -> new HttpTracingClient(client, brave);
    }

    HttpTracingClient(Client client, Brave brave) {
        super(client, Function.identity(), remoteInvoker -> new HttpTracingRemoteInvoker(remoteInvoker, brave));
    }

}
