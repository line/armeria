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

import java.io.File;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.Objects;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.validation.Valid;
import javax.validation.constraints.Min;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.jetty.HttpsConnectorFactory;

@JsonTypeName(ArmeriaHttpsConnectorFactory.TYPE)
public class ArmeriaHttpsConnectorFactory extends HttpsConnectorFactory
        implements ArmeriaServerDecorator {

    public static final String TYPE = "armeria-https";
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaHttpsConnectorFactory.class);

    /**
     * Builds an instance of {@link ArmeriaHttpsConnectorFactory} on port 8080
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
     * Builds an instance of {@code ArmeriaHttpsConnectorFactory} on port 8080
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
        final ArmeriaHttpsConnectorFactory factory = new ArmeriaHttpsConnectorFactory();
        factory.setPort(8080);
        factory.setKeyCertChainFile(keyCertChainPath);
        factory.setKeyStorePath(Objects.requireNonNull(keyStorePath, "keyStorePath must not be null"));
        factory.setKeyStorePassword(keyStorePassword);
        factory.setSelfSigned(certIsSelfSigned);
        return factory;
    }

    @JsonProperty
    private String keyCertChainFile;
    @JsonProperty
    private boolean selfSigned;

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

    public ArmeriaHttpsConnectorFactory() {
    }

    public String getKeyCertChainFile() {
        return keyCertChainFile;
    }

    public void setKeyCertChainFile(final String keyCertChainFile) {
        this.keyCertChainFile = keyCertChainFile;
    }

    public boolean isSelfSigned() {
        return selfSigned;
    }

    public void setSelfSigned(final boolean selfSigned) {
        this.selfSigned = selfSigned;
    }

    @Override
    public void decorate(ServerBuilder sb) throws SSLException, CertificateException {
        logger.debug("Building Armeria HTTPS Server");

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

    @VisibleForTesting
    ServerBuilder buildTlsServer(final ServerBuilder sb,
                                 final String keyCertChainFile,
                                 final String keyStoreFile) throws SSLException, CertificateException {
        final Collection<SessionProtocol> sessionProtocols = getSessionProtocols();
        boolean built = false;
        for (SessionProtocol protocol : sessionProtocols) {
            if (protocol.isTls()) {
                if (isSelfSigned()) {
                    sb.tlsSelfSigned();
                }
                final File _keyCertChainFile = new File(keyCertChainFile);
                final File _keyStoreFile = new File(keyStoreFile);
                try {
                    if (isValidKeyStorePassword()) {
                        sb.tls(_keyCertChainFile, _keyStoreFile, getKeyStorePassword());
                    } else {
                        logger.warn("keyStorePassword is not valid. Continuing to configure server without it");
                        sb.tls(_keyCertChainFile, _keyStoreFile);
                    }
                } catch (SSLException e) {
                    logger.error("Error building server with protocol " + protocol, e);
                    throw e;
                }
                // more TLS settings?
                built = true;
                break; // assuming that one cert+pass is good for all protocols
            }
        }
        if (!built) {
            throw new SSLException("No protocols in " + sessionProtocols + " use TLS.");
        }
        return sb;
    }

    public int getInitialConnectionWindowSize() {
        return initialConnectionWindowSize;
    }

    public void setInitialConnectionWindowSize(final int initialConnectionWindowSize) {
        this.initialConnectionWindowSize = initialConnectionWindowSize;
    }

    public int getInitialStreamingWindowSize() {
        return initialStreamingWindowSize;
    }

    public void setInitialStreamingWindowSize(final int initialStreamingWindowSize) {
        this.initialStreamingWindowSize = initialStreamingWindowSize;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public void setMaxFrameSize(final int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    public long getMaxStreamsPerConnection() {
        return maxStreamsPerConnection;
    }

    public void setMaxStreamsPerConnection(final long maxStreamsPerConnection) {
        this.maxStreamsPerConnection = maxStreamsPerConnection;
    }

    public long getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    public void setMaxHeaderListSize(final long maxHeaderListSize) {
        this.maxHeaderListSize = maxHeaderListSize;
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
