/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client.endpoint.healthcheck;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultHealthCheckParams implements HealthCheckerParams {

    private final String path;
    private final HttpMethod method;
    @Nullable
    private final String host;
    private final SessionProtocol protocol;
    private final int port;
    private final Endpoint endpoint;

    DefaultHealthCheckParams(String path, HttpMethod method, @Nullable String host,
                             SessionProtocol protocol, int port, Endpoint endpoint) {
        this.path = path;
        this.method = method;
        this.host = host;
        this.protocol = protocol;
        this.port = port;
        this.endpoint = endpoint;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public HttpMethod httpMethod() {
        return method;
    }

    @Override
    public @Nullable String host() {
        return host;
    }

    @Override
    public SessionProtocol protocol() {
        return protocol;
    }

    @Override
    public Endpoint endpoint() {
        if (port == 0) {
            return endpoint.withoutDefaultPort(protocol);
        } else if (port == protocol.defaultPort()) {
            return endpoint.withoutPort();
        } else {
            return endpoint.withPort(port);
        }
    }
}
