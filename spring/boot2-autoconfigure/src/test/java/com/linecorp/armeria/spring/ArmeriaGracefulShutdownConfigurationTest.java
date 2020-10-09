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
package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationTest.TestConfiguration;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * {@code application-gracefulShutdownTest.yml} will be loaded with minimal settings to make it work.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class, properties = "spring.main.web-application-type=none")
@ActiveProfiles({ "local", "gracefulShutdownTest" })
@DirtiesContext
public class ArmeriaGracefulShutdownConfigurationTest {

    @SpringBootApplication
    static class TestConfiguration {}

    @Inject
    @Nullable
    private Server server;

    @Value("${spring.lifecycle.timeout-per-shutdown-phase}")
    private Duration duration;

    @Test
    public void testGracefulShutdown() throws Exception {
        final long startTime = System.nanoTime();
        server.stop().join();
        assertThat(System.nanoTime() - startTime).isGreaterThanOrEqualTo(duration.toNanos());
    }
}
