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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.server.ServerRule;

public class RateLimitingThrottlingStrategyTest {
    @Rule
    public ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/rate-limit", SERVICE.decorate(ThrottlingHttpService.newDecorator(
                    new RateLimitingThrottlingStrategy<>(1)
            )));
        }
    };

    @Test
    public void rateLimit() throws Exception {
        HttpClient client = HttpClient.of(serverRule.uri("/"));
        assertThat(client.get("/rate-limit").aggregate().get().status())
                .isEqualTo(HttpStatus.OK);
        client.get("/rate-limit").aggregate().get();
        client.get("/rate-limit").aggregate().get();
        // Reached to limit
        assertThat(client.get("/rate-limit").aggregate().get().status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> HttpStatus.OK.equals(client.get("/rate-limit").aggregate().get().status()));
    }
}
