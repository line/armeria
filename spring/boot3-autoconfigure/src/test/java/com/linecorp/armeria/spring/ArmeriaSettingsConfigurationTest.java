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

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.ServerErrorHandler;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.spring.ArmeriaSettingsConfigurationTest.TestConfiguration;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "settings" })
@DirtiesContext
class ArmeriaSettingsConfigurationTest {

    private static final Object dummyObject = new Object();
    private static final HttpResponse dummyResponse = HttpResponse.of(200);

    @SpringBootApplication
    static class TestConfiguration {
        @Bean
        public ArmeriaServerConfigurator configurator() {
            return builder -> builder.gracefulShutdownTimeoutMillis(1000, 10000)
                                     .errorHandler((ctx, cause) -> {
                                         // Should never reach here because the bean is applied first.
                                         throw new Error();
                                     })
                                     .dependencyInjector(new DependencyInjector() {
                                         @Override
                                         public <T> T getInstance(Class<T> type) {
                                             // Should never reach here because the bean is applied first.
                                             throw new Error();
                                         }

                                         @Override
                                         public void close() {}
                                     }, true);
        }

        @Bean
        public ServerErrorHandler errorHandler() {
            return (ctx, cause) -> dummyResponse;
        }

        @Bean
        public DependencyInjector dependencyInjector() {
            return new DependencyInjector() {
                @Override
                public <T> T getInstance(Class<T> type) {
                    return type.cast(dummyObject);
                }

                @Override
                public void close() {}
            };
        }
    }

    @Inject
    @Nullable
    private Server server;

    @Test
    void buildServerBasedOnProperties() {
        assertThat(server).isNotNull();
        final ServerConfig config = server.config();

        assertThat(config.maxNumConnections()).isEqualTo(8);
        assertThat(config.idleTimeoutMillis()).isEqualTo(2000);
        assertThat(config.pingIntervalMillis()).isEqualTo(1000);
        assertThat(config.maxConnectionAgeMillis()).isEqualTo(4000);
        assertThat(config.maxNumRequestsPerConnection()).isEqualTo(4);

        assertThat(config.http2InitialConnectionWindowSize()).isEqualTo(2097152);
        assertThat(config.http2InitialStreamWindowSize()).isEqualTo(4194304);
        assertThat(config.http2MaxStreamsPerConnection()).isEqualTo(1);
        assertThat(config.http2MaxFrameSize()).isEqualTo(32768);
        assertThat(config.http2MaxHeaderListSize()).isEqualTo(16384);

        assertThat(config.http1MaxInitialLineLength()).isEqualTo(8192);
        assertThat(config.http1MaxHeaderSize()).isEqualTo(16384);
        assertThat(config.http1MaxChunkSize()).isEqualTo(32768);

        final VirtualHost defaultVirtualHost = config.defaultVirtualHost();
        assertThat(defaultVirtualHost.requestTimeoutMillis()).isEqualTo(8000);
        assertThat(defaultVirtualHost.maxRequestLength()).isEqualTo(0);
        assertThat(defaultVirtualHost.verboseResponses()).isTrue();

        // ArmeriaServerConfigurator overrides the properties from ArmeriaSettings.
        assertThat(config.gracefulShutdownTimeout().toMillis()).isEqualTo(10000);
        assertThat(config.gracefulShutdownQuietPeriod().toMillis()).isEqualTo(1000);

        assertThat(config.dependencyInjector().getInstance(Object.class)).isSameAs(dummyObject);
        assertThat(config.errorHandler().onServiceException(null, null)).isSameAs(dummyResponse);
    }
}
