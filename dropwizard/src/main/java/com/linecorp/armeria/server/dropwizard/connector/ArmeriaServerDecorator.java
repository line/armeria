/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.dropwizard.connector;

import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.validation.PortRange;

public interface ArmeriaServerDecorator {
    /**
     * Decorate a {@link ServerBuilder} with the configurations of this {@ConnectorFactory}.
     * By default, sets all SessionProtocols to bind to the given port.
     *
     * @param sb An instance of a {@link ServerBuilder}
     * @throws SSLException Thrown when configuring TLS
     * @throws CertificateException Thrown when validating certificates
     */
    default void decorate(ServerBuilder sb) throws SSLException, CertificateException {
        sb.port(getPort(), getSessionProtocols());
    }

    /**
     * Defines the port to bind all protocols to.
     * @return A valid port number
     */
    @PortRange
    int getPort();

    /**
     * Defines the Jackson polymorphic type for Dropwizard deserialization.
     *
     * @return The type label for Jackson deserialization
     */
    String getType();

    /**
     * Defines the {@link SessionProtocol}s for this ConnectorFactory.
     *
     * @return A collection of Armeria {@link SessionProtocol}s
     */
    default Collection<SessionProtocol> getSessionProtocols() {
        return Arrays.stream(getType().split("-"))
                     .filter(s -> !s.equals("armeria"))
                     .map(SessionProtocol::of)
                     .collect(Collectors.toSet());
    }
}
