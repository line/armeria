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

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationWithoutMeterTest.NoMeterTestConfiguration;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * application-autoConfTest.yml will be loaded with minimal settings to make it work.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = NoMeterTestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
public class ArmeriaAutoConfigurationWithoutMeterTest {

    @SpringBootApplication
    @Import(ArmeriaOkServiceConfiguration.class)
    public static class NoMeterTestConfiguration {
    }

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Inject
    private Server server;

    private String newUrl(String scheme) {
        final int port = server.activePort().get().localAddress().getPort();
        return scheme + "://127.0.0.1:" + port;
    }

    @Test
    public void testHttpServiceRegistrationBean() throws Exception {
        final HttpClient client = HttpClient.of(newUrl("h1c"));

        final HttpResponse response = client.get("/ok");

        final AggregatedHttpMessage msg = response.aggregate().get();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThat(msg.contentUtf8()).isEqualTo("ok");
    }
}
