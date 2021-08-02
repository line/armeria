/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.VirtualHost;

class ArmeriaSettingsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArmeriaAutoConfiguration.class))
            .withUserConfiguration(ServiceConfiguration.class);

    @Test
    void buildServerBasedOnProperties() {
        withPropertyValues(contextRunner)
                .run(context -> {
                    assertThat(context).hasSingleBean(Server.class);
                    final Server server = context.getBean(Server.class);
                    final ServerConfig config = server.config();
                    assertThat(config.maxNumConnections()).isEqualTo(2);
                    assertThat(config.idleTimeoutMillis()).isEqualTo(2000);
                    assertThat(config.pingIntervalMillis()).isEqualTo(1000);
                    assertThat(config.maxConnectionAgeMillis()).isEqualTo(4000);
                    assertThat(config.maxNumRequestsPerConnection()).isEqualTo(4);

                    assertThat(config.http2InitialConnectionWindowSize())
                            .isEqualTo(Flags.defaultHttp2InitialConnectionWindowSize() * 2);
                    assertThat(config.http2InitialStreamWindowSize())
                            .isEqualTo(Flags.defaultHttp2InitialStreamWindowSize() * 2);
                    assertThat(config.http2MaxStreamsPerConnection()).isEqualTo(8);
                    assertThat(config.http2MaxFrameSize())
                            .isEqualTo(Flags.defaultHttp2MaxFrameSize() * 2);
                    assertThat(config.http2MaxHeaderListSize())
                            .isEqualTo(Flags.defaultHttp2MaxHeaderListSize() * 2);

                    assertThat(config.http1MaxInitialLineLength())
                            .isEqualTo(Flags.defaultHttp1MaxInitialLineLength() * 2);
                    assertThat(config.http1MaxHeaderSize())
                            .isEqualTo(Flags.defaultHttp1MaxHeaderSize() * 2);
                    assertThat(config.http1MaxChunkSize())
                            .isEqualTo(Flags.defaultHttp1MaxChunkSize() * 2);

                    final VirtualHost defaultVirtualHost = config.defaultVirtualHost();
                    assertThat(defaultVirtualHost.requestTimeoutMillis())
                            .isEqualTo(Flags.defaultRequestTimeoutMillis() * 2);
                    assertThat(defaultVirtualHost.maxRequestLength())
                            .isEqualTo(Flags.defaultMaxRequestLength() * 2);
                    assertThat(defaultVirtualHost.verboseResponses()).isTrue();
                });
    }

    @Test
    void shouldConfigurePropertiesBeforeBean() {
        withPropertyValues(contextRunner)
                .withUserConfiguration(CustomConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Server.class);
                    final Server server = context.getBean(Server.class);
                    final ServerConfig config = server.config();
                    assertThat(config.maxNumConnections()).isEqualTo(Flags.maxNumConnections());
                });
    }

    private static ApplicationContextRunner withPropertyValues(ApplicationContextRunner contextRunner) {
        return contextRunner.withPropertyValues(property("maxNumConnections", 2),
                                                property("idleTimeoutMillis", 2000),
                                                property("pingIntervalMillis", 1000),
                                                property("maxConnectionAgeMillis", 4000),
                                                property("maxNumRequestsPerConnection", 4),
                                                property("http2InitialConnectionWindowSize",
                                                         Flags.defaultHttp2InitialConnectionWindowSize() * 2),
                                                property("http2InitialStreamWindowSize",
                                                         Flags.defaultHttp2InitialStreamWindowSize() * 2),
                                                property("http2MaxStreamsPerConnection", 8),
                                                property("http2MaxFrameSize",
                                                         Flags.defaultHttp2MaxFrameSize() * 2),
                                                property("http2MaxHeaderListSize",
                                                         Flags.defaultHttp2MaxHeaderListSize() * 2),
                                                property("http1MaxInitialLineLength",
                                                         Flags.defaultHttp1MaxInitialLineLength() * 2),
                                                property("http1MaxHeaderSize",
                                                         Flags.defaultHttp1MaxHeaderSize() * 2),
                                                property("http1MaxChunkSize",
                                                         Flags.defaultHttp1MaxChunkSize() * 2),
                                                property("requestTimeoutMillis",
                                                         Flags.defaultRequestTimeoutMillis() * 2),
                                                property("maxRequestLength",
                                                         Flags.defaultMaxRequestLength() * 2),
                                                property("verboseResponses", "true"));
    }

    private static String property(String key, int value) {
        return property(key, Integer.toString(value));
    }

    private static String property(String key, long value) {
        return property(key, Long.toString(value));
    }

    private static String property(String key, String value) {
        return "armeria." + key + "=" + value;
    }

    @Configuration
    static class ServiceConfiguration {
        @Bean
        ArmeriaServerConfigurator rootService() {
            return builder -> builder.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    }

    @Configuration
    static class CustomConfiguration {
        @Bean
        ArmeriaServerConfigurator customConfigurations() {
            return builder -> builder.maxNumConnections(Flags.maxNumConnections());
        }
    }
}
