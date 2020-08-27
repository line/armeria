/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.consul;

import java.net.URI;
import java.net.URISyntaxException;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.SessionProtocol;

public class ConsulClientBuilder {
    private static final SessionProtocol DEFAULT_CONSUL_PROTOCOL = SessionProtocol.HTTP;
    private static final String DEFAULT_CONSUL_ADDRESS = "127.0.0.1";
    private static final int DEFAULT_CONSUL_PORT = 8500;
    private static final String DEFAULT_CONSUL_API_VERSION = "v1";

    private SessionProtocol protocol = DEFAULT_CONSUL_PROTOCOL;
    private String address = DEFAULT_CONSUL_ADDRESS;
    private int port = DEFAULT_CONSUL_PORT;
    private String apiVersion = DEFAULT_CONSUL_API_VERSION;
    @Nullable
    private String token;

    ConsulClientBuilder() {
    }

    public ConsulClientBuilder protocol(SessionProtocol protocol) {
        this.protocol = protocol;
        return this;
    }

    public ConsulClientBuilder address(String address) {
        this.address = address;
        return this;
    }

    public ConsulClientBuilder port(int port) {
        this.port = port;
        return this;
    }

    public ConsulClientBuilder apiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
        return this;
    }

    public ConsulClientBuilder token(@Nullable String token) {
        this.token = token;
        return this;
    }

    public ConsulClient build() {
        try {
            final URI uri = new URI(protocol.uriText(), null, address, port, '/' + apiVersion, null, null);
            return new ConsulClient(uri, token);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Can not build URI for the Consul service", e);
        }
    }
}
