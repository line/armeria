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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.SessionProtocol;

public class ConsulClientBuilder {
    private static final SessionProtocol DEFAULT_CONSUL_PROTOCOL = SessionProtocol.HTTP;
    private static final String DEFAULT_CONSUL_ADDRESS = "127.0.0.1";
    private static final int DEFAULT_CONSUL_PORT = 8500;
    private static final String DEFAULT_CONSUL_API_VERSION = "v1";

    @Nullable
    private URI consulUri;
    private SessionProtocol consulProtocol = DEFAULT_CONSUL_PROTOCOL;
    private String consulAddress = DEFAULT_CONSUL_ADDRESS;
    private int consulPort = DEFAULT_CONSUL_PORT;
    private String consulApiVersion = DEFAULT_CONSUL_API_VERSION;
    @Nullable
    private String consulToken;
    private boolean flagToPreventConflict;

    protected ConsulClientBuilder() {
    }

    /**
     * Sets the specified Consul's API service URI.
     * The URI should include the Consul API version at path, like: /v1
     * @param consulUri the URI of Consul API service, default: HTTP://127.0.0.1:8500/v1
     */
    public ConsulClientBuilder consulUri(URI consulUri) {
        requireNonNull(consulUri, "consulUri");
        checkState(!flagToPreventConflict, "consulUri can't comes with other addressing options");
        this.consulUri = consulUri;
        return this;
    }

    /**
     * Sets the specified Consul's API service URI.
     * The URI should include the Consul API version at path, like: /v1
     * @param consulUri the URI of Consul API service, default: HTTP://127.0.0.1:8500/v1
     */
    public ConsulClientBuilder consulUri(String consulUri) {
        return consulUri(URI.create(requireNonNull(consulUri, "consulUri")));
    }

    /**
     * Sets the specified Consul's API service protocol scheme.
     * @param consulProtocol the protocol scheme of Consul API service, default: HTTP
     */
    public ConsulClientBuilder consulProtocol(SessionProtocol consulProtocol) {
        requireNonNull(consulProtocol, "consulProtocol");
        checkState(consulUri == null, "consulProtocol can't comes with consulUri");
        this.consulProtocol = consulProtocol;
        flagToPreventConflict = true;
        return this;
    }

    /**
     * Sets the specified Consul's API service host address.
     * @param consulAddress the host address of Consul API service, default: 127.0.0.1
     */
    public ConsulClientBuilder consulAddress(String consulAddress) {
        requireNonNull(consulAddress, "consulAddress");
        checkArgument(!consulAddress.isEmpty(), "consulAddress can't be empty");
        checkState(consulUri == null, "consulAddress can't comes with consulUri");
        this.consulAddress = consulAddress;
        flagToPreventConflict = true;
        return this;
    }

    /**
     * Sets the specified Consul's HTTP service port.
     * @param consulPort the port of Consul agent, default: 8500
     */
    public ConsulClientBuilder consulPort(int consulPort) {
        checkArgument(consulPort > 0, "consulPort can't be zero or negative");
        checkState(consulUri == null, "consulPort can't comes with consulUri");
        this.consulPort = consulPort;
        flagToPreventConflict = true;
        return this;
    }

    /**
     * Sets the specified Consul's API version.
     * @param consulApiVersion the version of Consul API service, default: v1
     */
    public ConsulClientBuilder consulApiVersion(String consulApiVersion) {
        requireNonNull(consulApiVersion, "consulApiVersion");
        checkArgument(!consulApiVersion.isEmpty(), "consulApiVersion can't be empty");
        checkState(consulUri == null, "consulApiVersion can't comes with consulUri");
        this.consulApiVersion = consulApiVersion;
        flagToPreventConflict = true;
        return this;
    }

    /**
     * Sets the specified token for Consul's API.
     * @param consulToken the token for accessing Consul API, default: null
     */
    public ConsulClientBuilder consulToken(String consulToken) {
        requireNonNull(consulToken, "consulToken");
        checkArgument(!consulToken.isEmpty(), "consulToken can't be empty");
        this.consulToken = consulToken;
        return this;
    }

    protected final ConsulClient buildClient() {
        final URI uri;
        if (consulUri != null) {
            uri = consulUri;
        } else {
            try {
                uri = new URI(consulProtocol.uriText(), null, consulAddress, consulPort,
                              '/' + consulApiVersion, null, null);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Can not build URI for the Consul service", e);
            }
        }
        return new ConsulClient(uri, consulToken);
    }
}
