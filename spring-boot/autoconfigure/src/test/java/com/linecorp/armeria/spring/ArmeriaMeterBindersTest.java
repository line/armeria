/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.spring;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.common.metric.MeterRegistryUtil.measure;
import static com.linecorp.armeria.common.metric.MeterRegistryUtil.measureAll;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationTest.TestConfiguration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.spring.export.prometheus.EnablePrometheusMetrics;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
@EnablePrometheusMetrics
public class ArmeriaMeterBindersTest {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaMeterBindersTest.class);

    @SpringBootApplication
    public static class TestConfiguration {

        @Bean
        public HttpServiceRegistrationBean okService() {
            return new HttpServiceRegistrationBean()
                    .setServiceName("okService")
                    .setService(new AbstractHttpService() {
                        @Override
                        protected void doGet(ServiceRequestContext ctx, HttpRequest req,
                                             HttpResponseWriter res) throws Exception {
                            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "ok");
                        }
                    })
                    .setPathMapping(PathMapping.ofExact("/ok"))
                    .setDecorator(LoggingService.newDecorator());
        }
    }

    @Inject
    private MeterRegistry registry;

    @Test
    public void testJvmMetrics() throws Exception {
        registry.getMeters().forEach(m -> {
            final String id = m.getName() +
                              Streams.stream(m.getTags()).collect(toImmutableMap(Tag::getKey, Tag::getValue));
            final Map<String, Double> values = measureAll(registry, m.getName(), m.getTags());
            if (values.size() > 1) {
                logger.debug("{}={}", id, values);
            } else {
                logger.debug("{}={}", id, values.values().iterator().next());
            }
        });

        assertThat(measure(registry, "jvm_buffer_count", "id", "direct")).isPositive();
        assertThat(measure(registry, "classes_loaded")).isPositive();
        assertThat(measure(registry, "jvm_memory_max", "id", "Code Cache")).isPositive();
        assertThat(measure(registry, "threads_daemon")).isPositive();
        assertThat(measure(registry, "cpu_total")).isPositive();
        assertThat(measure(registry, "logback_events", "level", "debug")).isPositive();
    }
}
