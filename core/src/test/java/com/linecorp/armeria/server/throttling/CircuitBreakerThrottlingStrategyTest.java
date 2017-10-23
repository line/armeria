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
package com.linecorp.armeria.server.throttling;

import static com.linecorp.armeria.server.throttling.ThrottlingServiceTest.SERVICE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.server.ServerRule;

public class CircuitBreakerThrottlingStrategyTest {
    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Rule
    public ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/always", SERVICE.decorate(ThrottlingHttpService.newDecorator(
                    new CircuitBreakerThrottlingStrategy<>(new CircuitBreaker() {
                        @Override
                        public String name() {
                            return "always";
                        }

                        @Override
                        public void onSuccess() {
                        }

                        @Override
                        public void onFailure(Throwable cause) {
                        }

                        @Override
                        public void onFailure() {
                        }

                        @Override
                        public boolean canRequest() {
                            return true;
                        }
                    }))))
              .service("/never", SERVICE.decorate(ThrottlingHttpService.newDecorator(
                      new CircuitBreakerThrottlingStrategy<>(new CircuitBreaker() {
                          @Override
                          public String name() {
                              return "never";
                          }

                          @Override
                          public void onSuccess() {
                          }

                          @Override
                          public void onFailure(Throwable cause) {
                          }

                          @Override
                          public void onFailure() {
                          }

                          @Override
                          public boolean canRequest() {
                              return false;
                          }
                      }))));
        }
    };

    @Test
    public void serve() throws Exception {
        HttpClient client = HttpClient.of(serverRule.uri("/"));
        assertThat(client.get("/never").aggregate().get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(client.get("/always").aggregate().get().status()).isEqualTo(HttpStatus.OK);
    }
}
