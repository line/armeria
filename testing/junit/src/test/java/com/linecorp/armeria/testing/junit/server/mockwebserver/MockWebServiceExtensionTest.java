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

package com.linecorp.armeria.testing.junit.server.mockwebserver;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

class MockWebServiceExtensionTest {

    @RegisterExtension
    static MockWebServerExtension server = new MockWebServerExtension();

    @Test
    void normal() {
        final HttpClient httpClient = HttpClient.of(server.httpUri("/"));
        final HttpClient httpsClient = new HttpClientBuilder(server.httpsUri("/"))
                .factory(new ClientFactoryBuilder()
                                 .sslContextCustomizer(
                                         ssl -> ssl.trustManager(InsecureTrustManagerFactory.INSTANCE))
                                 .build())
                .build();
        server.enqueue(AggregatedHttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "hello"));
        server.enqueue(MockResponse.of(AggregatedHttpResponse.of(HttpStatus.FORBIDDEN)));
        server.enqueue(AggregatedHttpResponse.of(HttpStatus.FOUND));

        assertThat(httpClient.get("/").aggregate().join()).satisfies(response -> {
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("hello");
        });

        assertThat(httpClient.post("/upload", "world").aggregate().join()).satisfies(response -> {
            assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
        });

        assertThat(httpsClient.get("/secure").aggregate().join()).satisfies(response -> {
            assertThat(response.status()).isEqualTo(HttpStatus.FOUND);
        });

        assertThat(httpClient.get("/not-queued").aggregate().join()).satisfies(response -> {
            assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        });

        assertThat(server.takeRequest()).satisfies(request -> {
            assertThat(request.getRequest().method()).isEqualTo(HttpMethod.GET);
            assertThat(request.getRequest().path()).isEqualTo("/");
            assertThat(request.getContext().sslSession()).isNull();
        });

        assertThat(server.takeRequest()).satisfies(request -> {
            assertThat(request.getRequest().method()).isEqualTo(HttpMethod.POST);
            assertThat(request.getRequest().path()).isEqualTo("/upload");
            assertThat(request.getRequest().contentUtf8()).isEqualTo("world");
            assertThat(request.getContext().sslSession()).isNull();
        });

        assertThat(server.takeRequest()).satisfies(request -> {
            assertThat(request.getRequest().method()).isEqualTo(HttpMethod.GET);
            assertThat(request.getRequest().path()).isEqualTo("/secure");
            assertThat(request.getContext().sslSession()).isNotNull();
        });

        assertThat(server.takeRequest()).satisfies(request -> {
            assertThat(request.getRequest().method()).isEqualTo(HttpMethod.GET);
            assertThat(request.getRequest().path()).isEqualTo("/not-queued");
            assertThat(request.getContext().sslSession()).isNull();
        });

        assertThat(server.takeRequest()).isNull();
    }

    @Test
    void delay() {
        server.enqueue(MockResponse.builder(AggregatedHttpResponse.of(HttpStatus.OK))
                                   .delay(Duration.ofMillis(200))
                                   .build());

        final HttpClient client = new HttpClientBuilder(server.httpUri("/"))
                .option(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(50L))
                .build();

        assertThatThrownBy(() -> client.get("/").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
    }

    // Following two tests are to make sure there is no state cross-talk between tests.
    @Test
    @Order(1)
    void leavesState() {
        server.enqueue(AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
        server.enqueue(AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
        server.enqueue(AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(HttpClient.of(server.httpUri("/")).get("/whoami").aggregate().join().status())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @Order(2)
    void doesNotSeeStateFromLeavesState() {
        // leavesState left two INTERNAL_SERVER_ERROR responses in the queue, but they should be gone now.
        server.enqueue(AggregatedHttpResponse.of(HttpStatus.OK));

        assertThat(HttpClient.of(server.httpUri("/")).get("/").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);

        // leavesState did not take the /whoami request, but it's gone now.
        assertThat(server.takeRequest().getRequest().path()).isEqualTo("/");
    }
}
