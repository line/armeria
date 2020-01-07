/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.dropwizard;

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.InetSocketAddress;

import javax.validation.Validator;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;

import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;

class ArmeriaConfigurationUtilTest {

    private final ObjectMapper objectMapper = Jackson.newObjectMapper();
    private final Validator validator = Validators.newValidator();
    private final YamlConfigurationFactory<ArmeriaSettings> configFactory =
            new YamlConfigurationFactory<>(ArmeriaSettings.class, validator, objectMapper, "dw");

    @Test
    void configureServer() throws Exception {
        final File yml = new File(resourceFilePath("armeria-settings.yaml"));
        final ArmeriaSettings armeriaSettings = configFactory.build(yml);
        armeriaSettings.setSsl(null);
        final ServerBuilder serverBuilder = Server.builder()
                .service("/foo", (ctx, req) -> HttpResponse.of(200));
        serverBuilder.tlsSelfSigned();
        ArmeriaConfigurationUtil.configureServer(serverBuilder, armeriaSettings);
        final Server server = serverBuilder.build();
        assertThat(server.defaultHostname()).isEqualTo("host.name.com");
        assertThat(server.config().maxNumConnections()).isEqualTo(5000);
        assertThat(server.config().isDateHeaderEnabled()).isFalse();
        assertThat(server.config().isServerHeaderEnabled()).isTrue();
        assertThat(server.config().defaultVirtualHost().maxRequestLength()).isEqualTo(10485761);

        assertThat(server.config().ports()).hasSize(3);
        assertThat(server.config().ports()).containsExactly(
                new ServerPort(8080, SessionProtocol.HTTP),
                new ServerPort(new InetSocketAddress("127.0.0.1", 8081), SessionProtocol.HTTPS),
                new ServerPort(8443, SessionProtocol.HTTPS, SessionProtocol.PROXY)
        );
        assertThat(server.config().http1MaxChunkSize()).isEqualTo(4000);
        assertThat(server.config().http1MaxInitialLineLength()).isEqualTo(4096);
        assertThat(server.config().http1MaxInitialLineLength()).isEqualTo(4096);
        assertThat(server.config().http2InitialConnectionWindowSize()).isEqualTo(1024 * 1024 * 2);
        assertThat(server.config().http2InitialStreamWindowSize()).isEqualTo(1024 * 1024 * 2);
        assertThat(server.config().http2MaxFrameSize()).isEqualTo(16385);
        assertThat(server.config().http2MaxHeaderListSize()).isEqualTo(8193);
        assertThat(server.config().proxyProtocolMaxTlvSize()).isEqualTo(65320);
    }
}
