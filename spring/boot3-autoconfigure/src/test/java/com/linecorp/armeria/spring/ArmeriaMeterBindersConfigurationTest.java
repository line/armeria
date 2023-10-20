/*
 * Copyright 2017 LINE Corporation
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

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;

import javax.inject.Inject;

import org.assertj.core.api.Condition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.spring.ArmeriaMeterBindersConfigurationTest.TestConfiguration;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
@Timeout(10)
class ArmeriaMeterBindersConfigurationTest {

    @SpringBootApplication
    @Import(ArmeriaOkServiceConfiguration.class)
    public static class TestConfiguration {
    }

    @Inject
    private MeterRegistry registry;

    @Test
    void testDefaultMetrics() throws Exception {
        final Map<String, Double> measurements = MoreMeters.measureAll(registry);
        assertThat(measurements).containsKeys(
                "jvm.buffer.count#value{id=direct}",
                "jvm.classes.loaded#value",
                "jvm.threads.daemon#value",
                "logback.events#count{level=debug}",
                "process.uptime#value",
                "system.cpu.count#value");

        // Use prefix-matching for meter IDs because JVM memory meters differ between JVM versions.
        assertThat(measurements).hasKeySatisfying(new Condition<>(
                key -> key.startsWith("jvm.memory.max#value{area=nonheap,id="),
                "MeterRegistry must contain JVM memory meters"));

        final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        boolean hasOpenFdCount = false;
        try {
            requireNonNull(os.getClass().getDeclaredMethod("getOpenFileDescriptorCount"));
            hasOpenFdCount = true;
        } catch (Exception ignored) {
            // Not supported
        }

        if (hasOpenFdCount) {
            assertThat(measurements).containsKeys("process.files.open#value");
        }
    }
}
