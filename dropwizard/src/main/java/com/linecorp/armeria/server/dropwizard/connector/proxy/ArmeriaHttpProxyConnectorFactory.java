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
import java.util.Objects;

import javax.net.ssl.SSLException;
import javax.validation.Valid;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaHttpConnectorFactory;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaServerDecorator;

import io.dropwizard.jetty.ConnectorFactory;

@JsonTypeName(ArmeriaHttpProxyConnectorFactory.TYPE)
public class ArmeriaHttpProxyConnectorFactory extends ArmeriaProxyConnectorFactory {

    public static final String TYPE = "armeria-http-proxy";
    // TODO: This is not configurable
    @JsonIgnore
    private final ArmeriaServerDecorator innerFactory;

    public ArmeriaHttpProxyConnectorFactory() {
        innerFactory = new ArmeriaHttpConnectorFactory();
    }

    /**
     * Builds an instance of {@link ArmeriaHttpProxyConnectorFactory} on port 8082.
     */
    public static @Valid ConnectorFactory build() {
        final ArmeriaHttpProxyConnectorFactory factory = new ArmeriaHttpProxyConnectorFactory();
        factory.setPort(8082);
        return factory;
    }

    @Override
    public void decorate(ServerBuilder sb) throws SSLException, CertificateException {
        super.decorate(sb);
        Objects.requireNonNull(innerFactory, "Proxying inner factory cannot be null");
        innerFactory.decorate(sb);
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
