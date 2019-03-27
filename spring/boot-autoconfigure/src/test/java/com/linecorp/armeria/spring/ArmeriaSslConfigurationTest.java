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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationTest.TestConfiguration;
import com.linecorp.armeria.testing.internal.MockAddressResolverGroup;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * application-sslTest.yml will be loaded with minimal settings to make it work.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "sslTest" })
@DirtiesContext
public class ArmeriaSslConfigurationTest {

    @SpringBootApplication
    public static class TestConfiguration {

        @Bean
        public HttpServiceRegistrationBean okService() {
            return new HttpServiceRegistrationBean()
                    .setServiceName("okService")
                    .setService(new OkService())
                    .setPathMapping(PathMapping.ofExact("/ok"))
                    .setDecorators(ImmutableList.of(LoggingService.newDecorator()));
        }
    }

    public static class OkService extends AbstractHttpService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "ok");
        }
    }

    private static final ClientFactory clientFactory =
            new ClientFactoryBuilder()
                    .sslContextCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE))
                    .addressResolverGroupFactory(eventLoopGroup -> MockAddressResolverGroup.localhost())
                    .build();

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Inject
    private Server server;

    private String newUrl(SessionProtocol protocol) {
        final int port = server.activePorts().values().stream()
                               .filter(p1 -> p1.hasProtocol(protocol)).findAny()
                               .flatMap(p -> Optional.of(p.localAddress().getPort()))
                               .orElseThrow(() -> new IllegalStateException(protocol + " port not open"));
        return protocol.uriText() + "://127.0.0.1:" + port;
    }

    private void verify(SessionProtocol protocol) {
        final HttpResponse response = HttpClient.of(clientFactory, newUrl(protocol)).get("/ok");

        final AggregatedHttpMessage msg = response.aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThat(msg.contentUtf8()).isEqualTo("ok");
    }

    @Test
    public void https() {
        verify(SessionProtocol.HTTPS);
    }

    @Test
    public void http() {
        verify(SessionProtocol.HTTP);
    }
}
