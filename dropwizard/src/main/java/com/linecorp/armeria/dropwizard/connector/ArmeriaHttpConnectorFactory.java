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

import javax.validation.Valid;
import javax.validation.constraints.Min;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.jetty.HttpConnectorFactory;
import io.dropwizard.util.Size;
import io.dropwizard.validation.MaxSize;
import io.dropwizard.validation.MinSize;

/**
 * A subclass of {@link HttpConnectorFactory} for Armeria.
 */
@JsonTypeName(ArmeriaHttpConnectorFactory.TYPE)
public class ArmeriaHttpConnectorFactory extends HttpConnectorFactory
        implements ArmeriaServerDecorator {

    public static final String TYPE = "armeria-http";
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaHttpConnectorFactory.class);

    /**
     * Builds an instance of {@link ArmeriaHttpConnectorFactory} on port 8080.
     */
    public static @Valid ConnectorFactory build() {
        final ArmeriaHttpConnectorFactory factory = new ArmeriaHttpConnectorFactory();
        factory.setPort(8080);
        return factory;
    }

    @JsonProperty
    private @MinSize(0) @MaxSize(Integer.MAX_VALUE) Size maxChunkSize =
            Size.bytes(Flags.defaultHttp1MaxChunkSize());

    @JsonProperty
    private @Min(0) int maxInitialLineLength = Flags.defaultHttp1MaxInitialLineLength();

    @Override
    public void decorate(ServerBuilder sb) {
        logger.debug("Building Armeria HTTP Server");
        buildHttpServer(sb).port(getPort(), getSessionProtocols());
    }

    @VisibleForTesting
    ServerBuilder buildHttpServer(ServerBuilder sb) {
        return sb.http1MaxHeaderSize((int) getMaxResponseHeaderSize().toBytes())
                 .http1MaxChunkSize((int) maxChunkSize.toBytes())
                 .http1MaxInitialLineLength(maxInitialLineLength);
        // more HTTP1 settings?
    }

    /**
     * Returns the maximum allowed length of an HTTP/1 request chunk, when chunked transfer encoding is used.
     */
    public Size getMaxChunkSize() {
        return maxChunkSize;
    }

    /**
     * Sets the maximum allowed length of an HTTP/1 request chunk, when chunked transfer encoding is used.
     */
    public void setMaxChunkSize(Size maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }

    /**
     * Returns the maximum allowed length of the initial line in an HTTP/1 request.
     */
    public int getMaxInitialLineLength() {
        return maxInitialLineLength;
    }

    /**
     * Sets the maximum allowed length of the initial line in an HTTP/1 request.
     */
    public void setMaxInitialLineLength(int maxInitialLineLength) {
        this.maxInitialLineLength = maxInitialLineLength;
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
