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
package com.linecorp.armeria.server.dropwizard;

import javax.validation.Valid;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.jetty.HttpsConnectorFactory;

@JsonTypeName(ArmeriaHttpsConnectorFactory.TYPE)
public class ArmeriaHttpsConnectorFactory extends HttpsConnectorFactory {
    public static final String TYPE = "armeria-https";

    @JsonProperty
    private String keyCertChainFile;

    @JsonProperty
    private boolean selfSigned;

    public ArmeriaHttpsConnectorFactory() {
    }

    /**
    * Builds an instance of {@code ArmeriaHttpsConnectorFactory} on port 8082.
    */
    public static @Valid ConnectorFactory build() {
        final ArmeriaHttpsConnectorFactory factory = new ArmeriaHttpsConnectorFactory();
        factory.setPort(8082);
        return factory;
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
}
