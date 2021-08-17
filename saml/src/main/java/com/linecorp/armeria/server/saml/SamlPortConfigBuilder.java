/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import static com.linecorp.armeria.server.saml.SamlPortConfig.isValidPort;
import static com.linecorp.armeria.server.saml.SamlPortConfig.validatePort;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerPort;

/**
 * A builder for a {@link SamlPortConfig}.
 */
final class SamlPortConfigBuilder {

    @Nullable
    private SessionProtocol scheme;
    private int port;

    /**
     * Returns a {@link SessionProtocol} for a SAML service port.
     */
    @Nullable
    SessionProtocol scheme() {
        return scheme;
    }

    /**
     * Returns a port number for a SAML service.
     */
    int port() {
        return port;
    }

    /**
     * Sets a {@link SessionProtocol} only if it has not been specified before.
     */
    SamlPortConfigBuilder setSchemeIfAbsent(SessionProtocol scheme) {
        requireNonNull(scheme, "scheme");
        if (this.scheme == null) {
            if (scheme == SessionProtocol.HTTPS ||
                scheme == SessionProtocol.HTTP) {
                this.scheme = scheme;
            } else {
                throw new IllegalArgumentException("unexpected session protocol: " + scheme);
            }
        }
        return this;
    }

    /**
     * Sets a port number only if it has not been specified before.
     */
    SamlPortConfigBuilder setPortIfAbsent(int port) {
        if (this.port == 0) {
            this.port = validatePort(port);
        }
        return this;
    }

    /**
     * Sets a {@link SessionProtocol} and its port number only if it has not been specified before.
     */
    SamlPortConfigBuilder setSchemeAndPortIfAbsent(ServerPort serverPort) {
        requireNonNull(serverPort, "serverPort");
        if (serverPort.hasHttps()) {
            setSchemeIfAbsent(SessionProtocol.HTTPS);
        } else if (serverPort.hasHttp()) {
            setSchemeIfAbsent(SessionProtocol.HTTP);
        } else {
            throw new IllegalArgumentException("unexpected session protocol: " + serverPort.protocols());
        }

        // Do not set a port if the port number is 0 which means that the port will be automatically chosen.
        final int port = serverPort.localAddress().getPort();
        if (isValidPort(port)) {
            setPortIfAbsent(port);
        }
        return this;
    }

    /**
     * Converts this builder to a {@link SamlPortConfigAutoFiller} in order to fill unspecified values
     * after the server started.
     */
    SamlPortConfigAutoFiller toAutoFiller() {
        return new SamlPortConfigAutoFiller(copyOf(this));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("scheme", scheme)
                          .add("port", port)
                          .toString();
    }

    private static SamlPortConfigBuilder copyOf(SamlPortConfigBuilder that) {
        final SamlPortConfigBuilder builder = new SamlPortConfigBuilder();
        if (that.scheme != null) {
            builder.scheme = that.scheme;
        }
        if (isValidPort(that.port)) {
            builder.port = that.port;
        }
        return builder;
    }
}
