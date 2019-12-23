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
package com.linecorp.armeria.dropwizard.connector.proxy;

import java.security.cert.CertificateException;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.dropwizard.connector.ArmeriaServerDecorator;
import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.util.Size;
import io.dropwizard.validation.MaxSize;
import io.dropwizard.validation.MinSize;

public abstract class ArmeriaProxyConnectorFactory implements ConnectorFactory, ArmeriaServerDecorator {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaProxyConnectorFactory.class);

    // Default from ServerBuilder
    @JsonProperty
    private @MinSize(0) @MaxSize(Integer.MAX_VALUE) Size maxTlvSize = Size.bytes(65535 - 216);

    @Nullable
    @Override
    public Connector build(Server server, MetricRegistry metrics, String name,
                           @Nullable ThreadPool threadPool) {
        return null; // un-used
    }

    @Override
    public void decorate(ServerBuilder sb) throws CertificateException, SSLException {
        logger.debug("Building Armeria Proxy Server");
        sb.port(getPort(), getSessionProtocols())
          .proxyProtocolMaxTlvSize((int) getMaxTlvSize().toBytes());
        // are there other proxy settings?
    }

    public Size getMaxTlvSize() {
        return maxTlvSize;
    }

    public void setMaxTlvSize(Size maxTlvSize) {
        this.maxTlvSize = maxTlvSize;
    }

    @Override
    public int getPort() {
        return 0;
    }
}
