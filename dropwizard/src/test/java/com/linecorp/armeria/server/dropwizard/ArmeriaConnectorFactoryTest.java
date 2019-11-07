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

import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import io.dropwizard.configuration.ConfigurationValidationException;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Discoverable;
import io.dropwizard.jackson.DiscoverableSubtypeResolver;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.jetty.ConnectorFactory;

class ArmeriaConnectorFactoryTest {
    private final ObjectMapper objectMapper = Jackson.newObjectMapper();
    private final Validator validator = Validators.newValidator();

    @Test
    public void typesAreDiscoverable() throws Exception {
        // Make sure the types we specified in META-INF gets picked up
        assertThat(new DiscoverableSubtypeResolver().getDiscoveredSubtypes())
                .contains(ArmeriaHttpConnectorFactory.class)
                .contains(ArmeriaHttpsConnectorFactory.class);
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
                    .isInstanceOf(Discoverable.class);
            assertThat(factory)
                    .isInstanceOf(ArmeriaHttpConnectorFactory.class);
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

        @Test
        public void withKeyStore_shouldBuild() throws Exception {
            final File yml = new File(resourceFilePath("yaml/connector/https-keystore.yaml"));
            final ConnectorFactory factory = configFactory.build(yml);
            assertThat(factory)
                    .isInstanceOf(Discoverable.class);
            assertThat(factory)
                    .isInstanceOf(ArmeriaHttpsConnectorFactory.class);

            final ArmeriaHttpsConnectorFactory httpsFactory = (ArmeriaHttpsConnectorFactory) factory;
            assertThat(httpsFactory.getKeyCertChainFile()).isBlank();
            assertThat(httpsFactory.isSelfSigned()).isFalse();
        }
    }
}
