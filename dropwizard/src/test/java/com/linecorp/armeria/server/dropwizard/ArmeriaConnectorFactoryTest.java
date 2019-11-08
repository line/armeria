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

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.security.cert.CertificateException;
import java.util.stream.Stream;

import javax.net.ssl.SSLException;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaH2ConnectorFactory;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaHttpConnectorFactory;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaHttpsConnectorFactory;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaServerDecorator;
import com.linecorp.armeria.server.dropwizard.connector.proxy.ArmeriaHttpProxyConnectorFactory;
import com.linecorp.armeria.server.dropwizard.connector.proxy.ArmeriaHttpsProxyConnectorFactory;
import com.linecorp.armeria.server.dropwizard.connector.proxy.ArmeriaProxyConnectorFactory;

import io.dropwizard.configuration.ConfigurationValidationException;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Discoverable;
import io.dropwizard.jackson.DiscoverableSubtypeResolver;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.util.Size;

class ArmeriaConnectorFactoryTest {
    private final ObjectMapper objectMapper = Jackson.newObjectMapper();
    private final Validator validator = Validators.newValidator();

    @ParameterizedTest
    @ArgumentsSource(ConnectorFactoryProvider.class)
    public void testDiscoverable(final Class<?> clz) throws Exception {
        // Make sure the types we specified in META-INF gets picked up
        assertThat(new DiscoverableSubtypeResolver().getDiscoveredSubtypes())
                .contains(clz);
    }

    @ParameterizedTest
    @ArgumentsSource(ConnectorFactoryProvider.class)
    public void testFactoriesAreConnectorFactoriesAndServerDecorators(final Class<?> clz) {
        assertThat(ConnectorFactory.class).isAssignableFrom(clz);
        assertThat(ArmeriaServerDecorator.class).isAssignableFrom(clz);
    }

    private <F extends ArmeriaHttpsConnectorFactory> void validateHttpsConnectorWithKeyStore(
            final YamlConfigurationFactory<F> configFactory,
            final File yml)
            throws java.io.IOException, io.dropwizard.configuration.ConfigurationException {
        final F factory = configFactory.build(yml);
        assertThat(factory)
                .isInstanceOf(Discoverable.class)
                .isInstanceOf(ArmeriaHttpsConnectorFactory.class);

        final ArmeriaHttpsConnectorFactory httpsFactory = (ArmeriaHttpsConnectorFactory) factory;
        assertThat(httpsFactory.isValidKeyStorePath()).isTrue();
        assertThat(httpsFactory.getKeyStorePath()).isEqualTo("/some/path/keystore.jks");
        assertThat(httpsFactory.isValidKeyStorePassword()).isTrue();
        assertThat(httpsFactory.getKeyStorePassword()).isEqualTo("changeme");
    }

    private void verifyHttpsConnectorWithoutKeyStoreIsInvalid(final YamlConfigurationFactory<?> configFactory,
                                                              final File yml) {
        final ConfigurationValidationException ex = assertThrows(
                ConfigurationValidationException.class, () -> configFactory.build(yml));

        final ImmutableList<ConstraintViolation<?>> constraintViolations =
                ex.getConstraintViolations().asList();
        assertThat(constraintViolations).isNotEmpty();
        assertThat(constraintViolations.size()).isEqualTo(2);
        assertThat(constraintViolations.stream()
                                       .map(ConstraintViolation::getMessage)
                                       .collect(toList()))
                .containsExactlyInAnyOrder("keyStorePassword should not be null or empty",
                                           "keyStorePath should not be null");
    }

    private static class ConnectorFactoryProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    ArmeriaHttpConnectorFactory.class,
                    ArmeriaHttpsConnectorFactory.class,
                    ArmeriaH2ConnectorFactory.class,
                    ArmeriaHttpProxyConnectorFactory.class,
                    ArmeriaHttpsProxyConnectorFactory.class
            ).map(Arguments::of);
        }
    }

    @Nested
    class ArmeriaHttpConnectorFactoryTest {
        private final YamlConfigurationFactory<ArmeriaHttpConnectorFactory> configFactory =
                new YamlConfigurationFactory<>(
                        ArmeriaHttpConnectorFactory.class, validator, objectMapper, "dw");

        @Test
        public void shouldBuild() throws Exception {
            final File yml = new File(resourceFilePath("yaml/connector/http-minimal.yaml"));
            final ConnectorFactory factory = configFactory.build(yml);
            assertThat(factory)
                    .isInstanceOf(Discoverable.class)
                    .isInstanceOf(ArmeriaHttpConnectorFactory.class);
        }

        @Test
        public void testFactoryDefaults() {
            final ArmeriaHttpConnectorFactory factory =
                    (ArmeriaHttpConnectorFactory) ArmeriaHttpConnectorFactory.build();
            assertThat(factory.getPort()).isEqualTo(8082);
            assertThat(factory.getSessionProtocols()).containsOnly(SessionProtocol.HTTP);
            assertThat(factory.getMaxInitialLineLength()).isEqualTo(Flags.defaultHttp1MaxInitialLineLength());
            assertThat(factory.getMaxChunkSize().toBytes()).isEqualTo(Flags.defaultHttp1MaxChunkSize());
            assertThat(factory.getMaxResponseHeaderSize()).isEqualByComparingTo(Size.kilobytes(8));
        }
    }

    @Nested
    class ArmeriaHttpsConnectorFactoryTest {
        private final YamlConfigurationFactory<ArmeriaHttpsConnectorFactory> configFactory =
                new YamlConfigurationFactory<>(
                        ArmeriaHttpsConnectorFactory.class, validator, objectMapper, "dw");

        @Test
        public void withoutKeyStore_shouldNotValidate() throws Exception {
            final File yml = new File(resourceFilePath("yaml/connector/https-minimal.yaml"));
            verifyHttpsConnectorWithoutKeyStoreIsInvalid(configFactory, yml);
        }

        @Test
        public void withKeyStore_shouldBuild() throws Exception {
            final File yml = new File(resourceFilePath("yaml/connector/https-keystore.yaml"));
            validateHttpsConnectorWithKeyStore(configFactory, yml);
        }

        @Test
        public void testFactoryDefaults() {
            final String f1 = ResourceHelpers.resourceFilePath("f.crt");
            final String f2 = ResourceHelpers.resourceFilePath("f.jks");

            final ArmeriaHttpsConnectorFactory factory =
                    (ArmeriaHttpsConnectorFactory) ArmeriaHttpsConnectorFactory.build(f1, f2, null);
            assertThat(factory.getPort()).isEqualTo(8082);
            assertThat(factory.getSessionProtocols()).containsOnly(SessionProtocol.HTTPS);
            assertThat(factory.getKeyCertChainFile()).isEqualTo(f1);
            assertThat(factory.getKeyStorePath()).isEqualTo(f2);
            assertThat(factory.isSelfSigned()).isFalse();
        }
    }

    @Nested
    class ArmeriaH2ConnectorFactoryTest {
        private final YamlConfigurationFactory<ArmeriaH2ConnectorFactory> configFactory =
                new YamlConfigurationFactory<>(
                        ArmeriaH2ConnectorFactory.class, validator, objectMapper, "dw");

        @Test
        public void withoutKeyStore_shouldNotValidate() throws Exception {
            final File yml = new File(resourceFilePath("yaml/connector/h2-minimal.yaml"));
            verifyHttpsConnectorWithoutKeyStoreIsInvalid(configFactory, yml);
        }

        @Test
        public void shouldBuild() throws Exception {
            final File yml = new File(resourceFilePath("yaml/connector/h2-keystore.yaml"));
            final ConnectorFactory factory = configFactory.build(yml);
            assertThat(factory)
                    .isInstanceOf(Discoverable.class)
                    .isInstanceOf(ArmeriaH2ConnectorFactory.class);
        }

        @Test
        public void testFactoryDefaults() throws CertificateException, SSLException {
            final String f1 = ResourceHelpers.resourceFilePath("f.crt");
            final String f2 = ResourceHelpers.resourceFilePath("f.jks");
            final ArmeriaH2ConnectorFactory factory =
                    (ArmeriaH2ConnectorFactory) ArmeriaH2ConnectorFactory.build(f1, f2, null);
            assertThat(factory.getPort()).isEqualTo(8082);
            assertThat(factory.getSessionProtocols()).containsOnly(SessionProtocol.H2);
            assertThat(factory.getInitialConnectionWindowSize())
                    .isEqualTo(Flags.defaultHttp2InitialConnectionWindowSize());
            assertThat(factory.getInitialStreamingWindowSize())
                    .isEqualTo(Flags.defaultHttp2InitialStreamWindowSize());
            assertThat(factory.getMaxStreamsPerConnection())
                    .isEqualTo(Flags.defaultHttp2MaxStreamsPerConnection());
            assertThat(factory.getMaxFrameSize())
                    .isEqualTo(Flags.defaultHttp2MaxFrameSize());
            assertThat(factory.getMaxHeaderListSize())
                    .isEqualTo(Flags.defaultHttp2MaxHeaderListSize());
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class ArmeriaHttpProxyConnectorFactoryTest {
        private final YamlConfigurationFactory<ArmeriaHttpProxyConnectorFactory> configFactory =
                new YamlConfigurationFactory<>(
                        ArmeriaHttpProxyConnectorFactory.class, validator, objectMapper, "dw");

        @Mock
        private org.eclipse.jetty.server.Server server;

        @Mock
        private MetricRegistry registry;

        @Test
        public void returnsNullJettyServer() {
            final ConnectorFactory factory = ArmeriaHttpProxyConnectorFactory.build();
            assertThat(factory.build(server, registry, "junit", null)).isNull();
        }

        @Test
        public void shouldBuild() throws Exception {
            final File yml = new File(resourceFilePath("yaml/connector/http-proxy-minimal.yaml"));
            final ConnectorFactory factory = configFactory.build(yml);
            assertThat(factory)
                    .isInstanceOf(Discoverable.class)
                    .isInstanceOf(ArmeriaProxyConnectorFactory.class)
                    .isInstanceOf(ArmeriaHttpProxyConnectorFactory.class);
        }

        @Test
        public void testFactoryDefaults() {
            final ArmeriaProxyConnectorFactory factory =
                    (ArmeriaProxyConnectorFactory) ArmeriaHttpProxyConnectorFactory.build();
            assertThat(factory.getPort()).isEqualTo(8082);
            assertThat(factory.getSessionProtocols())
                    .containsOnly(SessionProtocol.HTTP, SessionProtocol.PROXY);
            assertThat(factory.getMaxTlvSize().toBytes()).isEqualTo(65535 - 216);
        }

        @Test
        public void testMaxTlvSize() {
            final ArmeriaProxyConnectorFactory factory =
                    (ArmeriaProxyConnectorFactory) ArmeriaHttpProxyConnectorFactory.build();
            final Size size = Size.kilobytes(42);
            factory.setMaxTlvSize(size);
            assertThat(factory.getMaxTlvSize()).isEqualTo(size);
        }
    }
}
