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

import com.fasterxml.jackson.annotation.JsonTypeName;

import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.jetty.HttpConnectorFactory;

@JsonTypeName(ArmeriaHttpConnectorFactory.TYPE)
public class ArmeriaHttpConnectorFactory extends HttpConnectorFactory {
    public static final String TYPE = "armeria-http";

    /**
    * Builds an instance of {@code ArmeriaHttpConnectorFactory} on port 8082.
    */
    public static @Valid ConnectorFactory build() {
        final ArmeriaHttpConnectorFactory factory = new ArmeriaHttpConnectorFactory();
        factory.setPort(8082);
        return factory;
    }
}
