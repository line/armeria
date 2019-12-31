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
package com.linecorp.armeria.dropwizard.connector;

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

/**
 * A subclass of {@link HttpsConnectorFactory} for Armeria.
 */
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
    public static @Valid ConnectorFactory build(String keyCertChainPath,
                                                String keyStorePath,
                                                @Nullable String keyStorePassword) {
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
    public static @Valid ConnectorFactory build(String keyCertChainPath,
                                                String keyStorePath,
                                                @Nullable String keyStorePassword,
                                                boolean certIsSelfSigned) {
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
    private @Min(0) int initialStreamWindowSize = Flags.defaultHttp2InitialStreamWindowSize();
    @JsonProperty
    private @Min(0) int maxFrameSize = Flags.defaultHttp2MaxFrameSize();
    @JsonProperty
    private @Min(0L) long maxStreamsPerConnection = Flags.defaultHttp2MaxStreamsPerConnection();
    @JsonProperty
    private @Min(0L) long maxHeaderListSize = Flags.defaultHttp2MaxHeaderListSize();

    /**
     * Returns the TLS certificate chain file.
     */
    public String getKeyCertChainFile() {
        return keyCertChainFile;
    }

    /**
     * Sets the TLS certificate chain file.
     */
    public void setKeyCertChainFile(String keyCertChainFile) {
        this.keyCertChainFile = keyCertChainFile;
    }

    /**
     * Returns whether to generate a self-signed TLS key pair.
     */
    public boolean isSelfSigned() {
        return selfSigned;
    }

    /**
     * Sets whether to generate a self-signed TLS key pair.
     */
    public void setSelfSigned(boolean selfSigned) {
        this.selfSigned = selfSigned;
    }

    @Override
    public void decorate(ServerBuilder sb) throws SSLException, CertificateException {
        logger.debug("Building Armeria HTTPS Server");

        sb.port(getPort(), getSessionProtocols())
          .http2InitialConnectionWindowSize(initialConnectionWindowSize)
          .http2InitialStreamWindowSize(initialStreamWindowSize)
          .http2MaxFrameSize(maxFrameSize)
          .http2MaxStreamsPerConnection(maxStreamsPerConnection)
          .http2MaxHeaderListSize(maxHeaderListSize);
        buildTlsServer(sb, getKeyCertChainFile(),
                       Objects.requireNonNull(getKeyStorePath(), TYPE + " keyStorePath must not be null"));
        // more HTTP/2 settings?
    }

    @VisibleForTesting
    ServerBuilder buildTlsServer(ServerBuilder sb,
                                 String keyCertChainFile,
                                 String keyStoreFile) throws SSLException, CertificateException {
        final Collection<SessionProtocol> sessionProtocols = getSessionProtocols();
        boolean built = false;
        for (SessionProtocol protocol : sessionProtocols) {
            if (protocol.isTls()) {
                if (isSelfSigned()) {
                    sb.tlsSelfSigned();
                }
                final File _keyCertChainFile = new File(keyCertChainFile);
                final File _keyStoreFile = new File(keyStoreFile);
                if (isValidKeyStorePassword()) {
                    sb.tls(_keyCertChainFile, _keyStoreFile, getKeyStorePassword());
                } else {
                    logger.warn("keyStorePassword is not valid. Continuing to configure server without it");
                    sb.tls(_keyCertChainFile, _keyStoreFile);
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

    /**
     * Returns the initial connection-level HTTP/2 flow control window size.
     *
     * @see ServerBuilder#http2InitialConnectionWindowSize(int)
     */
    public int getInitialConnectionWindowSize() {
        return initialConnectionWindowSize;
    }

    /**
     * Sets the initial connection-level HTTP/2 flow control window size.
     *
     * @see ServerBuilder#http2InitialConnectionWindowSize(int)
     */
    public void setInitialConnectionWindowSize(int initialConnectionWindowSize) {
        this.initialConnectionWindowSize = initialConnectionWindowSize;
    }

    /**
     * Returns the initial stream-level HTTP/2 flow control window size.
     *
     * @see ServerBuilder#http2InitialStreamWindowSize(int)
     */
    public int getInitialStreamWindowSize() {
        return initialStreamWindowSize;
    }

    /**
     * Sets the initial stream-level HTTP/2 flow control window size.
     *
     * @see ServerBuilder#http2InitialStreamWindowSize(int)
     */
    public void setInitialStreamWindowSize(int initialStreamWindowSize) {
        this.initialStreamWindowSize = initialStreamWindowSize;
    }

    /**
     * Returns the maximum size of HTTP/2 frame that can be received.
     *
     * @see ServerBuilder#http2MaxFrameSize(int)
     */
    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    /**
     * Sets the maximum size of HTTP/2 frame that can be received.
     *
     * @see ServerBuilder#http2MaxFrameSize(int)
     */
    public void setMaxFrameSize(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    /**
     * Returns the maximum number of concurrent streams per HTTP/2 connection.
     *
     * @see ServerBuilder#http2MaxStreamsPerConnection(long)
     */
    public long getMaxStreamsPerConnection() {
        return maxStreamsPerConnection;
    }

    /**
     * Sets the maximum number of concurrent streams per HTTP/2 connection.
     *
     * @see ServerBuilder#http2MaxStreamsPerConnection(long)
     */
    public void setMaxStreamsPerConnection(long maxStreamsPerConnection) {
        this.maxStreamsPerConnection = maxStreamsPerConnection;
    }

    /**
     * Returns the maximum size of HTTP/2 headers that can be received.
     *
     * @see ServerBuilder#http2MaxHeaderListSize(long)
     */
    public long getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    /**
     * Sets the maximum size of HTTP/2 headers that can be received.
     *
     * @see ServerBuilder#http2MaxHeaderListSize(long)
     */
    public void setMaxHeaderListSize(long maxHeaderListSize) {
        this.maxHeaderListSize = maxHeaderListSize;
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
