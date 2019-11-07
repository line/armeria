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

package com.linecorp.armeria.spring.actuate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfigurationTest.TestConfiguration;

import io.prometheus.client.exporter.common.TextFormat;
import reactor.test.StepVerifier;

/**
 * This uses {@link com.linecorp.armeria.spring.ArmeriaAutoConfiguration} for integration tests.
 * {@code application-autoConfTest.yml} will be loaded with minimal settings to make it work.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
@EnableAutoConfiguration
@ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
public class ArmeriaSpringActuatorAutoConfigurationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private static final String TEST_LOGGER_NAME = "com.linecorp.armeria.spring.actuate.testing.TestLogger";

    // We use this logger to test the /loggers endpoint, so set the name manually instead of using class name.
    @SuppressWarnings("unused")
    private static final Logger TEST_LOGGER = LoggerFactory.getLogger(TEST_LOGGER_NAME);

    static class SettableHealthIndicator implements HealthIndicator {

        private volatile Health health = Health.up().build();

        void setHealth(Health health) {
            this.health = health;
        }

        @Override
        public Health health() {
            return health;
        }
    }

    @SpringBootApplication
    public static class TestConfiguration {
        @Bean
        public SettableHealthIndicator settableHealth() {
            return new SettableHealthIndicator();
        }

        @Bean
        public ArmeriaServerConfigurator serverConfigurator() {
            return sb -> sb.requestTimeoutMillis(TIMEOUT_MILLIS);
        }
    }

    private static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

    @Inject
    private Server server;

    @Inject
    private SettableHealthIndicator settableHealth;

    private HttpClient client;

    @Before
    public void setUp() {
        client = HttpClient.of(newUrl("h2c"),
                               ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(TIMEOUT_MILLIS),
                               ClientOption.MAX_RESPONSE_LENGTH.newValue(0L));
        settableHealth.setHealth(Health.up().build());
    }

    private String newUrl(String scheme) {
        final int port = server.activeLocalPort();
        return scheme + "://127.0.0.1:" + port;
    }

    @Test
    public void testHealth() throws Exception {
        final AggregatedHttpResponse res = client.get("/internal/actuator/health").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(ArmeriaSpringActuatorAutoConfiguration.ACTUATOR_MEDIA_TYPE);

        final Map<String, Object> values = OBJECT_MAPPER.readValue(res.content().array(), JSON_MAP);
        assertThat(values).containsEntry("status", "UP");
    }

    @Test
    public void testHealth_down() throws Exception {
        settableHealth.setHealth(Health.down().build());
        final AggregatedHttpResponse res = client.get("/internal/actuator/health").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        final Map<String, Object> values = OBJECT_MAPPER.readValue(res.content().array(), JSON_MAP);
        assertThat(values).containsEntry("status", "DOWN");
    }

    @Test
    public void testLoggers() throws Exception {
        final String loggerPath = "/internal/actuator/loggers/" + TEST_LOGGER_NAME;
        AggregatedHttpResponse res = client.get(loggerPath).aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(ArmeriaSpringActuatorAutoConfiguration.ACTUATOR_MEDIA_TYPE);

        Map<String, Object> values = OBJECT_MAPPER.readValue(res.content().array(), JSON_MAP);
        assertThat(values).containsEntry("effectiveLevel", "DEBUG");

        res = client.execute(RequestHeaders.of(HttpMethod.POST, loggerPath,
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8),
                             OBJECT_MAPPER.writeValueAsBytes(ImmutableMap.of("configuredLevel", "info")))
                    .aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.NO_CONTENT);

        res = client.get(loggerPath).aggregate().get();
        values = OBJECT_MAPPER.readValue(res.content().array(), JSON_MAP);
        assertThat(values).containsEntry("effectiveLevel", "INFO");

        client.post(loggerPath, OBJECT_MAPPER.writeValueAsBytes(ImmutableMap.of()))
              .aggregate().get();
    }

    @Test
    public void testPrometheus() throws Exception {
        final AggregatedHttpResponse res = client.get("/internal/actuator/prometheus").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.parse(TextFormat.CONTENT_TYPE_004));
        assertThat(res.contentAscii()).startsWith("# HELP ");
    }

    @Test
    public void testHeapDump() throws Exception {
        final HttpResponse res = client.get("/internal/actuator/heapdump");
        final AtomicLong remainingBytes = new AtomicLong();
        StepVerifier.create(res)
                    .assertNext(obj -> {
                        assertThat(obj).isInstanceOf(ResponseHeaders.class);
                        final ResponseHeaders headers = (ResponseHeaders) obj;
                        assertThat(headers.status()).isEqualTo(HttpStatus.OK);
                        assertThat(headers.contentType()).isEqualTo(MediaType.OCTET_STREAM);
                        assertThat(headers.get(HttpHeaderNames.CONTENT_DISPOSITION))
                                .startsWith("attachment;filename=heapdump");
                        final long contentLength = headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1);
                        assertThat(contentLength).isPositive();
                        remainingBytes.set(contentLength);
                    })
                    .thenConsumeWhile(obj -> {
                        assertThat(obj).isInstanceOf(HttpData.class);
                        final HttpData data = (HttpData) obj;
                        final long newRemainingBytes = remainingBytes.addAndGet(-data.length());
                        assertThat(newRemainingBytes).isNotNegative();
                        return newRemainingBytes > 0; // Stop at the last HttpData.
                    })
                    .expectNextCount(1) // Skip the last HttpData.
                    .verifyComplete();

        assertThat(remainingBytes).hasValue(0);
    }

    @Test
    public void testLinks() throws Exception {
        final AggregatedHttpResponse res = client.get("/internal/actuator").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(ArmeriaSpringActuatorAutoConfiguration.ACTUATOR_MEDIA_TYPE);
        final Map<String, Object> values = OBJECT_MAPPER.readValue(res.content().array(), JSON_MAP);
        assertThat(values).containsKey("_links");
    }

    @Test
    public void testMissingMediaType() throws Exception {
        final String loggerPath = "/internal/actuator/loggers/" + TEST_LOGGER_NAME;
        final AggregatedHttpResponse res =
                client.execute(RequestHeaders.of(HttpMethod.POST, loggerPath),
                               OBJECT_MAPPER.writeValueAsBytes(ImmutableMap.of("configuredLevel", "debug")))
                      .aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    public void testInvalidMediaType() throws Exception {
        final String loggerPath = "/internal/actuator/loggers/" + TEST_LOGGER_NAME;
        final AggregatedHttpResponse res =
                client.execute(RequestHeaders.of(HttpMethod.POST, loggerPath,
                                                 HttpHeaderNames.CONTENT_TYPE, MediaType.PROTOBUF),
                               OBJECT_MAPPER.writeValueAsBytes(ImmutableMap.of("configuredLevel", "info")))
                      .aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
}
