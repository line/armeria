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
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationDisabledTest.TestConfiguration;

/**
 * This test {@link ArmeriaAutoConfiguration} could be disabled.
 * application-disabled.yml will set armeria.server-enabled to false:
 */
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "disabled" })
class ArmeriaAutoConfigurationDisabledTest {

    @SpringBootApplication
    public static class TestConfiguration {
    }

    @Inject
    private ApplicationContext applicationContext;

    @Test
    void serverNotCreated() throws Exception {
        assertThat(applicationContext.getBeansOfType(Server.class)).isEmpty();
    }
}
