/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.server.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitState;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Ticker;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class CircuitBreakerServiceTest {

    static final HttpService OK_SERVICE = new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    };

    static final HttpService BAD_SERVICE = new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }
    };

    static final class MockTicker implements Ticker {
        private long value = 0;

        @Override
        public long read() {
            return value;
        }

        public void set(long newValue) {
            value = newValue;
        }
    }

    final MockTicker ticker = new MockTicker();

    private final CircuitBreaker defaultCircuitBreaker =
            CircuitBreaker
                    .builder()
                    .minimumRequestThreshold(10)
                    .counterUpdateInterval(Duration.ofMillis(10))
                    .ticker(ticker).build();

    @Rule
    public ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/http-always",
                       OK_SERVICE.decorate(CircuitBreakerService.newDecorator(defaultCircuitBreaker)));
            sb.service("/http-never",
                       BAD_SERVICE.decorate(CircuitBreakerService.newDecorator(defaultCircuitBreaker)));
        }
    };

    @Test
    public void statusBasedOnCircuitState() throws Exception {
        final WebClient client = WebClient.of(serverRule.httpUri());

        defaultCircuitBreaker.enterState(CircuitState.CLOSED);
        assertThat(client.get("/http-always").aggregate().get().status())
                .isEqualTo(HttpStatus.OK);

        defaultCircuitBreaker.enterState(CircuitState.FORCED_OPEN);
        assertThat(client.get("/http-always").aggregate().get().status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    public void circuitTripsAutomatically() throws Exception {
        final WebClient client = WebClient.of(serverRule.httpUri());

        defaultCircuitBreaker.enterState(CircuitState.CLOSED);
        assertThat(client.get("/http-never").aggregate().get().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        for (int i = 0; i < 100; i++) {
            assertThat(client.get("/http-never").aggregate().get().status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            ticker.set(ticker.value + 10L);
        }

        assertThat(client.get("/http-never").aggregate().get().status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
