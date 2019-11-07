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
package com.linecorp.armeria.server.dropwizard.logging;

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;

import javax.validation.Validator;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Discoverable;
import io.dropwizard.jackson.DiscoverableSubtypeResolver;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;

class AccessLogWriterFactoryTest {
    private final ObjectMapper objectMapper = Jackson.newObjectMapper();
    private final Validator validator = Validators.newValidator();

    @Test
    public void typesAreDiscoverable() throws Exception {
        // Make sure the types we specified in META-INF gets picked up
        assertThat(new DiscoverableSubtypeResolver().getDiscoveredSubtypes())
                .contains(CommonAccessLogWriterFactory.class)
                .contains(CombinedAccessLogWriterFactory.class)
                .contains(CustomAccessLogWriterFactory.class);
    }

    @Nested
    class CommonAccessLogWriterFactoryTest {
        private final YamlConfigurationFactory<CommonAccessLogWriterFactory> configFactory =
                new YamlConfigurationFactory<>(
                        CommonAccessLogWriterFactory.class, validator, objectMapper, "dw");

        @Test
        public void shouldBuildCommonLogger() throws Exception {
            final File yml = new File(resourceFilePath("yaml/accessLogWriter/common.yaml"));
            final AccessLogWriterFactory factory = configFactory.build(yml);
            assertThat(factory)
                    .isInstanceOf(Discoverable.class);
            assertThat(factory)
                    .isInstanceOf(CommonAccessLogWriterFactory.class);
            assertThat(factory.getWriter()).isNotNull();
        }
    }

    @Nested
    class CombinedAccessLogWriterFactoryTest {
        private final YamlConfigurationFactory<CombinedAccessLogWriterFactory> configFactory =
                new YamlConfigurationFactory<>(
                        CombinedAccessLogWriterFactory.class, validator, objectMapper, "dw");

        @Test
        public void shouldBuildCombinedLogger() throws Exception {
            final File yml = new File(resourceFilePath("yaml/accessLogWriter/combined.yaml"));
            final AccessLogWriterFactory factory = configFactory.build(yml);
            assertThat(factory)
                    .isInstanceOf(Discoverable.class);
            assertThat(factory)
                    .isInstanceOf(CombinedAccessLogWriterFactory.class);
            assertThat(factory.getWriter()).isNotNull();
        }
    }

    @Nested
    class CustomAccessLogWriterFactoryTest {
        private final YamlConfigurationFactory<CustomAccessLogWriterFactory> configFactory =
                new YamlConfigurationFactory<>(
                        CustomAccessLogWriterFactory.class, validator, objectMapper, "dw");

        @Test
        public void invalidFormat_throwsIllegalArgumentException() throws Exception {
            final File yml = new File(resourceFilePath("yaml/accessLogWriter/custom-bad-format.yaml"));
            final AccessLogWriterFactory factory = configFactory.build(yml);

            final String format = ((CustomAccessLogWriterFactory) factory).getFormat();
            assertThat(format).isNotBlank();

            final String badToken = "d";
            assertThat(format).isEqualTo("date:%" + badToken);
            final IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, () -> factory.getWriter());
            assertThat(ex.getMessage())
                    .isEqualTo(String.format("Unexpected token character: '%s'", badToken));
        }

        @Test
        public void shouldBuildCustomLogger() throws Exception {
            final File yml = new File(resourceFilePath("yaml/accessLogWriter/custom.yaml"));
            final AccessLogWriterFactory factory = configFactory.build(yml);
            assertThat(factory)
                    .isInstanceOf(Discoverable.class);
            assertThat(factory)
                    .isInstanceOf(CustomAccessLogWriterFactory.class);

            final String format = ((CustomAccessLogWriterFactory) factory).getFormat();
            assertThat(format).isNotBlank();
            assertThat(format).isEqualTo("%{BASIC_ISO_DATE}t");

            assertThat(factory.getWriter()).isNotNull();
        }
    }
}
