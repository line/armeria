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

package com.linecorp.armeria.testing.junit.server.mock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

class MockWebServiceExtensionTest {

    @RegisterExtension
    static MockWebServerExtension server = new MockWebServerExtension();

    @Test
    void normal() {
        final WebClient webClient = WebClient.of(server.httpUri());
        final WebClient httpsClient = WebClient.builder(server.httpsUri())
                                               .factory(ClientFactory.insecure())
                                               .build();

        server.enqueue(AggregatedHttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "hello"));
        server.enqueue(AggregatedHttpResponse.of(HttpStatus.FORBIDDEN).toHttpResponse());
        server.enqueue(AggregatedHttpResponse.of(HttpStatus.FOUND));

        assertThat(webClient.get("/").aggregate().join()).satisfies(response -> {
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("hello");
        });

        assertThat(webClient.post("/upload", "world").aggregate().join()).satisfies(response -> {
            assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
        });

        assertThat(httpsClient.get("/secure").aggregate().join()).satisfies(response -> {
            assertThat(response.status()).isEqualTo(HttpStatus.FOUND);
        });

        assertThat(webClient.get("/not-queued").aggregate().join()).satisfies(response -> {
            assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        });

        assertThat(server.takeRequest()).satisfies(request -> {
            assertThat(request.request().method()).isEqualTo(HttpMethod.GET);
            assertThat(request.request().path()).isEqualTo("/");
            assertThat(request.context().sslSession()).isNull();
        });

        assertThat(server.takeRequest()).satisfies(request -> {
            assertThat(request.request().method()).isEqualTo(HttpMethod.POST);
            assertThat(request.request().path()).isEqualTo("/upload");
            assertThat(request.request().contentUtf8()).isEqualTo("world");
            assertThat(request.context().sslSession()).isNull();
        });

        assertThat(server.takeRequest()).satisfies(request -> {
            assertThat(request.request().method()).isEqualTo(HttpMethod.GET);
            assertThat(request.request().path()).isEqualTo("/secure");
            assertThat(request.context().sslSession()).isNotNull();
        });

        assertThat(server.takeRequest()).satisfies(request -> {
            assertThat(request.request().method()).isEqualTo(HttpMethod.GET);
            assertThat(request.request().path()).isEqualTo("/not-queued");
            assertThat(request.context().sslSession()).isNull();
        });

        assertThat(server.takeRequest(0, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void delay() {
        server.enqueue(HttpResponse.delayed(AggregatedHttpResponse.of(HttpStatus.OK).toHttpResponse(),
                                            Duration.ofSeconds(1)));
        server.enqueue(HttpResponse.delayed(AggregatedHttpResponse.of(HttpStatus.OK), Duration.ofSeconds(1)));

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .option(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(50L))
                         .build();

        assertThatThrownBy(() -> client.get("/").aggregate().join())
                .hasCauseInstanceOf(ResponseTimeoutException.class);
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

        assertThat(WebClient.of(server.httpUri()).get("/whoami").aggregate().join().status())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @Order(2)
    void doesNotSeeStateFromLeavesState() {
        // leavesState left two INTERNAL_SERVER_ERROR responses in the queue, but they should be gone now.
        server.enqueue(AggregatedHttpResponse.of(HttpStatus.OK));

        assertThat(WebClient.of(server.httpUri()).get("/").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);

        // leavesState did not take the /whoami request, but it's gone now.
        assertThat(server.takeRequest().request().path()).isEqualTo("/");
    }
}
