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
import java.util.Objects;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.validation.Valid;
import javax.validation.constraints.Min;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.jetty.ConnectorFactory;

@JsonTypeName(ArmeriaH2ConnectorFactory.TYPE)
public class ArmeriaH2ConnectorFactory extends ArmeriaHttpsConnectorFactory {

    public static final String TYPE = "armeria-h2";
    private static final Logger LOGGER = LoggerFactory.getLogger(ArmeriaH2ConnectorFactory.class);

    /**
     * Builds an instance of {@link ArmeriaH2ConnectorFactory} on port 8082
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
     * Builds an instance of {@link ArmeriaH2ConnectorFactory} on port 8082
     * and sets if a self-signed certificate is used.
     *
     * @param keyCertChainPath path to a key cert chain
     * @param keyStorePath path to a keystore
     * @param keyStorePassword password to the keystore, or null
     * @param certIsSelfSigned see {@link ArmeriaH2ConnectorFactory#setSelfSigned}
     */
    public static @Valid ConnectorFactory build(final String keyCertChainPath,
                                                final String keyStorePath,
                                                @Nullable final String keyStorePassword,
                                                final boolean certIsSelfSigned) {
        final ArmeriaH2ConnectorFactory factory = new ArmeriaH2ConnectorFactory();
        factory.setPort(8082);
        factory.setKeyCertChainFile(keyCertChainPath);
        factory.setKeyStorePath(Objects.requireNonNull(keyStorePath, "keyStorePath must not be null"));
        factory.setKeyStorePassword(keyStorePassword);
        factory.setSelfSigned(certIsSelfSigned);
        return factory;
    }

    @JsonProperty
    private @Min(0) int initialConnectionWindowSize = Flags.defaultHttp2InitialConnectionWindowSize();
    @JsonProperty
    private @Min(0) int initialStreamingWindowSize = Flags.defaultHttp2InitialStreamWindowSize();
    @JsonProperty
    private @Min(0) int maxFrameSize = Flags.defaultHttp2MaxFrameSize();
    @JsonProperty
    private @Min(0L) long maxStreamsPerConnection = Flags.defaultHttp2MaxStreamsPerConnection();
    @JsonProperty
    private @Min(0L) long maxHeaderListSize = Flags.defaultHttp2MaxHeaderListSize();

    public ArmeriaH2ConnectorFactory() {
    }

    @Override
    public void decorate(ServerBuilder sb) throws SSLException, CertificateException {
        LOGGER.debug("Building Armeria H2 Server");
        buildTlsServer(sb, getKeyCertChainFile(),
                       Objects.requireNonNull(getKeyStorePath(), TYPE + " keyStorePath must not be null"))
                .port(getPort(), getSessionProtocols())
                .http2InitialConnectionWindowSize(initialConnectionWindowSize)
                .http2InitialStreamWindowSize(initialStreamingWindowSize)
                .http2MaxFrameSize(maxFrameSize)
                .http2MaxStreamsPerConnection(maxStreamsPerConnection)
                .http2MaxHeaderListSize(maxHeaderListSize);
        // more HTTP/2 settings?
    }

    public int getInitialConnectionWindowSize() {
        return initialConnectionWindowSize;
    }

    public void setInitialConnectionWindowSize(int initialConnectionWindowSize) {
        this.initialConnectionWindowSize = initialConnectionWindowSize;
    }

    public int getInitialStreamingWindowSize() {
        return initialStreamingWindowSize;
    }

    public void setInitialStreamingWindowSize(int initialStreamingWindowSize) {
        this.initialStreamingWindowSize = initialStreamingWindowSize;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public void setMaxFrameSize(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    public long getMaxStreamsPerConnection() {
        return maxStreamsPerConnection;
    }

    public void setMaxStreamsPerConnection(long maxStreamsPerConnection) {
        this.maxStreamsPerConnection = maxStreamsPerConnection;
    }

    public long getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    public void setMaxHeaderListSize(long maxHeaderListSize) {
        this.maxHeaderListSize = maxHeaderListSize;
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
