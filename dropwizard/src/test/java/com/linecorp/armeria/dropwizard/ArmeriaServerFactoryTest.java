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
package com.linecorp.armeria.dropwizard;

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.validation.Validator;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.dropwizard.connector.ArmeriaHttpConnectorFactory;
import com.linecorp.armeria.dropwizard.connector.ArmeriaHttpsConnectorFactory;
import com.linecorp.armeria.dropwizard.connector.ArmeriaServerDecorator;
import com.linecorp.armeria.dropwizard.logging.AccessLogWriterFactory;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Discoverable;
import io.dropwizard.jackson.DiscoverableSubtypeResolver;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.server.ServerFactory;
import io.dropwizard.server.SimpleServerFactory;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Size;

@ExtendWith(MockitoExtension.class)
class ArmeriaServerFactoryTest {
    private static final AccessLogWriterFactory DISABLED_LOG_WRITER = new AccessLogWriterFactory() {
        @Override
        public AccessLogWriter getWriter() {
            return AccessLogWriter.disabled();
        }
    };
    private final ObjectMapper objectMapper = Jackson.newObjectMapper();
    private final Validator validator = Validators.newValidator();
    private final YamlConfigurationFactory<ArmeriaServerFactory> configFactory =
            new YamlConfigurationFactory<>(ArmeriaServerFactory.class, validator, objectMapper, "dw");

    @Spy
    private MetricRegistry metricRegistry;

    @ParameterizedTest
    @ArgumentsSource(ServerFactoryProvider.class)
    public void typesAreDiscoverable(final Class<?> clz) throws Exception {
        // Make sure the types we specified in META-INF gets picked up
        assertThat(new DiscoverableSubtypeResolver().getDiscoveredSubtypes())
                .contains(clz);
    }

    @ParameterizedTest
    @ArgumentsSource(ServerFactoryProvider.class)
    public void typesAreServerFactories(final Class<?> clz) throws Exception {
        assertThat(ServerFactory.class).isAssignableFrom(clz);
    }

    private Server buildServer(ArmeriaServerFactory factory,
                               @Nullable ConnectorFactory connectorFactory,
                               @Nullable AccessLogWriterFactory logWriterFactory) {
        Objects.requireNonNull(factory);
        if (connectorFactory != null) {
            factory.setConnector(connectorFactory);
        }
        if (logWriterFactory != null) {
            factory.setAccessLogWriter(logWriterFactory);
        }
        final Environment environment = new Environment("dw", objectMapper, validator, metricRegistry,
                                                        getClass().getClassLoader());
        return factory.build(environment);
    }

    @Test
    public void testServerFactoryDecorator() throws Exception {
        final ArmeriaServerFactory factory = new ArmeriaServerFactory();
        final ServerBuilder sb = com.linecorp.armeria.server.Server.builder()
                                                                   .service("/", (ctx, req) -> HttpResponse
                                                                           .of(HttpStatus.OK));

        final ClassCastException ex =
                assertThrows(ClassCastException.class,
                             () -> ArmeriaServerFactory
                                     .decorateServerBuilder(sb, new ConnectorFactory() {
                                         @Override
                                         public Connector build(
                                                 Server server,
                                                 MetricRegistry metrics,
                                                 String name,
                                                 @Nullable ThreadPool threadPool) {
                                             return null;
                                         }
                                     }, null, null));
        assertThat(ex.getMessage()).isEqualTo("server.connector.type must be an instance of " +
                                              ArmeriaServerDecorator.class.getName());
    }

    @Test
    public void testManualFactoryBuilder() {
        final ArmeriaServerFactory factory = new ArmeriaServerFactory();
        final ConnectorFactory connector = ArmeriaHttpConnectorFactory.build();
        final Server server = buildServer(factory, connector, DISABLED_LOG_WRITER);

        assertThat(server).isNotNull();
        assertThat(server.getServer()).isNotNull();
        assertThat(server.getConnectors()).isEmpty();

        assertThat(factory.getConnector()).isInstanceOf(ArmeriaHttpConnectorFactory.class);

        assertThat(factory.getServerBuilder()).isNotNull();

        assertThat(factory.getAccessLogWriter()).isNotNull();
        assertThat(factory.getAccessLogWriter()).isSameAs(DISABLED_LOG_WRITER);
    }

    @Test
    public void testManualFactoryBuilderWithSetters() throws Exception {
        final ArmeriaServerFactory factory = new ArmeriaServerFactory();
        final ConnectorFactory connector = ArmeriaHttpConnectorFactory.build();

        factory.setDateHeaderEnabled(false);
        factory.setServerHeaderEnabled(false);
        factory.setVerboseResponses(true);
        factory.setJerseyEnabled(false);

        factory.setMaxRequestLength(Size.gigabytes(1));
        factory.setMaxNumConnections(1);

        final Server jettyServer = buildServer(factory, connector, DISABLED_LOG_WRITER);

        final com.linecorp.armeria.server.Server server = factory.getServerBuilder().build();
        server.stop().get(100, TimeUnit.MILLISECONDS);
        final ServerConfig config = server.config();

        assertThat(config.maxNumConnections())
                .isEqualTo(factory.getMaxNumConnections());
        assertThat(config.defaultVirtualHost().maxRequestLength())
                .isEqualTo(factory.getMaxRequestLength().toBytes());
    }

    private static class ServerFactoryProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(ArmeriaServerFactory.class).map(Arguments::of);
        }
    }

    @Nested
    class HttpServerFactoryTest {
        @Test
        public void shouldBuildHttpArmeriaServerFactory() throws Exception {
            final File yml = new File(resourceFilePath("yaml/server/http-server-minimal.yaml"));
            final ServerFactory serverFactory = configFactory.build(yml);
            assertThat(serverFactory)
                    .isInstanceOf(Discoverable.class);
            assertThat(serverFactory)
                    .isInstanceOf(SimpleServerFactory.class);
            assertThat(serverFactory)
                    .isInstanceOf(ArmeriaServerFactory.class);

            final ArmeriaServerFactory armeriaServerFactory = (ArmeriaServerFactory) serverFactory;
            assertThat(armeriaServerFactory.getAccessLogWriter().getWriter())
                    .isEqualTo(AccessLogWriter.common());
            assertThat(armeriaServerFactory.getMaxRequestLength().toBytes())
                    .isEqualTo(Flags.defaultMaxRequestLength());

            final ConnectorFactory connector = armeriaServerFactory.getConnector();
            assertThat(connector)
                    .isInstanceOf(ArmeriaHttpConnectorFactory.class);
        }
    }

    @Nested
    class HttpsServerFactoryTest {
        @Test
        public void shouldBuildHttpArmeriaServerFactory() throws Exception {
            final File yml = new File(resourceFilePath("yaml/server/https-server-minimal.yaml"));
            final ServerFactory serverFactory = configFactory.build(yml);
            assertThat(serverFactory)
                    .isInstanceOf(Discoverable.class);
            assertThat(serverFactory)
                    .isInstanceOf(ArmeriaServerFactory.class);

            final ArmeriaServerFactory armeriaServerFactory = (ArmeriaServerFactory) serverFactory;
            assertThat(armeriaServerFactory.getAccessLogWriter().getWriter())
                    .isEqualTo(AccessLogWriter.common());
            assertThat(armeriaServerFactory.getMaxRequestLength().toBytes())
                    .isEqualTo(Flags.defaultMaxRequestLength());

            final ConnectorFactory connector = armeriaServerFactory.getConnector();
            assertThat(connector)
                    .isInstanceOf(ArmeriaHttpsConnectorFactory.class);
        }
    }
}
