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

import javax.validation.Validator;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.dropwizard.ArmeriaSettings.Port;

import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;

class ArmeriaSettingsTest {

    private final ObjectMapper objectMapper = Jackson.newObjectMapper();
    private final Validator validator = Validators.newValidator();
    private final YamlConfigurationFactory<ArmeriaSettings> configFactory =
            new YamlConfigurationFactory<>(ArmeriaSettings.class, validator, objectMapper, "dw");

    @Test
    void parseArmeriaSettings() throws Exception {
        final File yml = new File(resourceFilePath("armeria-settings.yaml"));
        final ArmeriaSettings armeriaSettings = configFactory.build(yml);

        assertThat(armeriaSettings.getGracefulShutdownQuietPeriodMillis()).isEqualTo(5000);
        assertThat(armeriaSettings.getGracefulShutdownTimeoutMillis()).isEqualTo(40000);

        // Port
        final Port port1 = new Port();
        port1.setPort(8080);
        port1.setProtocol(SessionProtocol.HTTP);
        final Port port2 = new Port();
        port2.setIp("127.0.0.1");
        port2.setPort(8081);
        port2.setProtocol(SessionProtocol.HTTPS);
        final Port port3 = new Port();
        port3.setPort(8443);
        port3.setProtocols(ImmutableList.of(SessionProtocol.HTTPS, SessionProtocol.PROXY));
        assertThat(armeriaSettings.getPorts()).containsExactly(port1, port2, port3);

        // SSL
        assertThat(armeriaSettings.getSsl().getKeyAlias()).isEqualTo("host.name.com");
        assertThat(armeriaSettings.getSsl().getKeyStore()).isEqualTo("classpath:keystore.jks");
        assertThat(armeriaSettings.getSsl().getKeyStorePassword()).isEqualTo("changeme");
        assertThat(armeriaSettings.getSsl().getTrustStore()).isEqualTo("classpath:truststore.jks");
        assertThat(armeriaSettings.getSsl().getTrustStorePassword()).isEqualTo("changeme");

        // Compression
        assertThat(armeriaSettings.getCompression().isEnabled()).isTrue();
        assertThat(armeriaSettings.getCompression().getMimeTypes())
                .containsExactly("text/*", "application/json");
        assertThat(armeriaSettings.getCompression().getExcludedUserAgents())
                .containsExactly("some-user-agent", "another-user-agent");
        assertThat(armeriaSettings.getCompression().getMinResponseSize()).isEqualTo("1KB");

        // HTTP/1
        assertThat(armeriaSettings.getHttp1().getMaxChunkSize()).isEqualTo("4000");
        assertThat(armeriaSettings.getHttp1().getMaxInitialLineLength()).isEqualTo(4096);

        // HTTP/2
        assertThat(armeriaSettings.getHttp2().getInitialConnectionWindowSize()).isEqualTo("2MB");
        assertThat(armeriaSettings.getHttp2().getInitialStreamWindowSize()).isEqualTo("2MB");
        assertThat(armeriaSettings.getHttp2().getMaxFrameSize()).isEqualTo("16385");
        assertThat(armeriaSettings.getHttp2().getMaxHeaderListSize()).isEqualTo("8193");

        // PROXY
        assertThat(armeriaSettings.getProxy().getMaxTlvSize()).isEqualTo("65320");

        // Access Log
        assertThat(armeriaSettings.getAccessLog().getType()).isEqualTo("custom");
        assertThat(armeriaSettings.getAccessLog().getFormat()).isEqualTo("%h %l %u %t \"%r\" %s");
    }
}
