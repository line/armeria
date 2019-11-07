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
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import javax.validation.Validator;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Discoverable;
import io.dropwizard.jackson.DiscoverableSubtypeResolver;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.server.ServerFactory;

class ArmeriaServerFactoryTest {
    private final ObjectMapper objectMapper = Jackson.newObjectMapper();
    private final Validator validator = Validators.newValidator();
    private final YamlConfigurationFactory<ArmeriaServerFactory> configFactory =
            new YamlConfigurationFactory<>(ArmeriaServerFactory.class, validator, objectMapper, "dw");

    @Test
    public void typesAreDiscoverable() throws Exception {
        // Make sure the types we specified in META-INF gets picked up
        assertThat(new DiscoverableSubtypeResolver().getDiscoveredSubtypes())
                .contains(ArmeriaServerFactory.class);
    }

    @Test
    public void shouldBuildArmeriaServerFactory() throws Exception {
        final File yml = new File(resourceFilePath("yaml/server/server-minimal.yaml"));
        final ServerFactory serverFactory = configFactory.build(yml);
        assertThat(serverFactory)
                .isInstanceOf(Discoverable.class);
        assertThat(serverFactory)
                .isInstanceOf(ArmeriaServerFactory.class);
    }
}
