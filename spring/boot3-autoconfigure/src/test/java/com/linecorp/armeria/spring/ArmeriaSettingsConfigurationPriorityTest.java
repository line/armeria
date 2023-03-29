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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.spring.ArmeriaSettingsConfigurationPriorityTest.TestConfiguration;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "settings" })
@DirtiesContext
public class ArmeriaSettingsConfigurationPriorityTest {

    @SpringBootApplication
    static class TestConfiguration {
        @Bean
        ArmeriaServerConfigurator maxNumConnectionsConfigurator() {
            return builder -> builder.maxNumConnections(16);
        }
    }

    @Inject
    @Nullable
    private Server server;

    @Test
    public void shouldConfigurePropertiesBeforeBean() {
        assertThat(server).isNotNull();
        final ServerConfig config = server.config();

        assertThat(config.maxNumConnections()).isEqualTo(16);
    }
}
