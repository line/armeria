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
package com.linecorp.armeria.server.dropwizard.connector.proxy;

import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.validation.Valid;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaHttpsConnectorFactory;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaServerDecorator;

import io.dropwizard.jetty.ConnectorFactory;

@JsonTypeName(ArmeriaHttpsProxyConnectorFactory.TYPE)
public class ArmeriaHttpsProxyConnectorFactory extends ArmeriaProxyConnectorFactory {

    public static final String TYPE = "armeria-https-proxy";

    /**
     * Builds an instance of {@link ArmeriaHttpsProxyConnectorFactory} on port 8082
     * which does not use a self-signed certificate.
     *
     * @param keyCertChainPath path to a key cert chain
     * @param keyStorePath path to a keystore
     * @param keyStorePassword password to the keystore, or null
     */
    public static @Valid ConnectorFactory build(final String keyCertChainPath,
                                                final String keyStorePath,
                                                @Nullable final String keyStorePassword) {
        return build(keyCertChainPath, keyStorePath, keyStorePassword, false);
    }

    /**
     * Builds an instance of {@link ArmeriaHttpsProxyConnectorFactory} on port 8082
     * and sets if a self-signed certificate is used.
     *
     * @param keyCertChainPath path to a key cert chain
     * @param keyStorePath path to a keystore
     * @param keyStorePassword password to the keystore, or null
     * @param certIsSelfSigned see {@link ArmeriaHttpsConnectorFactory#setSelfSigned}
     */
    public static @Valid ConnectorFactory build(final String keyCertChainPath,
                                                final String keyStorePath,
                                                @Nullable final String keyStorePassword,
                                                final boolean certIsSelfSigned) {
        final ArmeriaHttpsProxyConnectorFactory factory = new ArmeriaHttpsProxyConnectorFactory();
        factory.setPort(8082);
        final ArmeriaHttpsConnectorFactory innerFactory = (ArmeriaHttpsConnectorFactory) factory.innerFactory;
        innerFactory.setKeyCertChainFile(keyCertChainPath);
        innerFactory.setKeyStorePath(Objects.requireNonNull(keyStorePath, "keyStorePath must not be null"));
        innerFactory.setKeyStorePassword(keyStorePassword);
        innerFactory.setSelfSigned(certIsSelfSigned);
        return factory;
    }

    // TODO: This is not configurable
    @JsonIgnore
    private final ArmeriaServerDecorator innerFactory;

    public ArmeriaHttpsProxyConnectorFactory() {
        innerFactory = new ArmeriaHttpsConnectorFactory();
    }

    @Override
    public void decorate(ServerBuilder sb) throws SSLException, CertificateException {
        super.decorate(sb);
        Objects.requireNonNull(innerFactory, "Proxying inner factory cannot be null");
        innerFactory.decorate(sb);
    }

    @Override
    public Collection<SessionProtocol> getSessionProtocols() {
        return Arrays.stream(TYPE.split("-")).filter(s -> !s.equals("armeria"))
                     .map(SessionProtocol::of)
                     .collect(Collectors.toSet());
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
