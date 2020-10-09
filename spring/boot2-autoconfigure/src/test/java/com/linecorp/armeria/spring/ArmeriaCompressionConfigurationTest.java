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
package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.spring.ArmeriaCompressionConfigurationTest.TestConfiguration;
import com.linecorp.armeria.spring.ArmeriaSettings.Compression;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * {@code application-compressionTest.yml} will be loaded with minimal settings to make it work.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class, properties = "spring.main.web-application-type=none")
@ActiveProfiles({ "local", "compressionTest" })
@DirtiesContext
public class ArmeriaCompressionConfigurationTest {

    @SpringBootApplication
    public static class TestConfiguration {
        @Bean
        public ArmeriaServerConfigurator armeriaServerConfigurator() {
            return sb -> sb.annotatedService(new Object() {
                @Get("/hello")
                public String hello(@Param Optional<Integer> size) {
                    return Strings.repeat("a", size.orElse(1));
                }
            });
        }
    }

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Inject
    @Nullable
    private Server server;

    @Inject
    @Nullable
    private ArmeriaSettings settings;

    private String newUrl() {
        assert server != null;
        return "http://127.0.0.1:" + server.activeLocalPort();
    }

    private static HttpRequest request(int sizeParam) {
        return HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/hello?size=" + sizeParam,
                                                HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
    }

    @Test
    public void compressionConfiguration() {
        assertThat(settings).isNotNull();
        final Compression compression = settings.getCompression();
        assertThat(compression).isNotNull();
        assertThat(compression.isEnabled()).isTrue();
        assertThat(compression.getMimeTypes()).containsExactly("text/*", "application/json");
        assertThat(compression.getExcludedUserAgents())
                .containsExactly("some-user-agent", "another-user-agent");
        assertThat(compression.getMinResponseSize()).isEqualTo("1KB");
    }

    @Test
    public void compressedResponse() {
        final AggregatedHttpResponse res =
                WebClient.of(newUrl()).execute(request(2048)).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    }

    @Test
    public void nonCompressedResponse() {
        final AggregatedHttpResponse res =
                WebClient.of(newUrl()).execute(request(1023)).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
    }
}
