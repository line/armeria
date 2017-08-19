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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.server.ServerRule;

public class TokenBucketThrottlingStrategyTest {
    @Rule
    public ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/token-bucket", SERVICE.decorate(ThrottlingHttpService.newDecorator(
                    new TokenBucketThrottlingStrategy<>(4, 5, Duration.ofSeconds(1))
            )));
        }
    };

    @Test
    public void tokenBucket() throws Exception {
        HttpClient client = HttpClient.of(serverRule.uri("/"));
        assertThat(client.get("/token-bucket").aggregate().get().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(client.get("/token-bucket").aggregate().get().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(client.get("/token-bucket").aggregate().get().status())
                .isEqualTo(HttpStatus.OK);
        assertThat(client.get("/token-bucket").aggregate().get().status())
                .isEqualTo(HttpStatus.OK);
        // bucket is now empty.
        assertThat(client.get("/token-bucket").aggregate().get().status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> HttpStatus.OK.equals(client.get("/token-bucket").aggregate().get().status()));
        // bucket is refilled.
        assertThat(client.get("/token-bucket").aggregate().get().status())
                .isEqualTo(HttpStatus.OK);
    }
}
