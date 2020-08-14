/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.util.Lists;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class ServerListenerTest {
    private static long STARTING_AT;
    private static long STARTED_AT;
    private static long STOPPING_AT;
    private static long STOPPED_AT;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.meterRegistry(PrometheusMeterRegistries.newRegistry())
              .service("/", (req, ctx) -> HttpResponse.of("Hello!"));

            // Record when the method triggered
            final ServerListener sl =
                    ServerListener.builder()
                                  // add a callback.
                                  .whenStarting((Server server) ->
                                          STARTING_AT = System.currentTimeMillis())
                                  // add multiple callbacks, one by one.
                                  .whenStarted((Server server) ->
                                          STARTED_AT = -1)
                                  .whenStarted((Server server) ->
                                          STARTED_AT = System.currentTimeMillis())
                                  // add multiple callbacks at once, with vargs api.
                                  .whenStopping((Server server) ->
                                                    STOPPING_AT = System.currentTimeMillis(),
                                                (Server server) -> STARTING_AT = 0L)
                                  // add multiple callbacks at once, with iterable api.
                                  .whenStopped(
                                    Lists.newArrayList((Server server) ->
                                                    STOPPED_AT = System.currentTimeMillis(),
                                                    (Server server) -> STARTED_AT = 0L))
                                  .build();
            sb.serverListener(sl);
        }
    };

    @Test
    public void testServerListener() throws Exception {
        // Before stop
        assertThat(STARTING_AT).isGreaterThan(0L);
        assertThat(STARTED_AT).isGreaterThanOrEqualTo(STARTING_AT);
        assertThat(STOPPING_AT).isEqualTo(0L);
        assertThat(STOPPED_AT).isEqualTo(0L);

        final Server server = ServerListenerTest.server.server();
        server.stop().get();

        // After stop
        assertThat(STARTING_AT).isEqualTo(0L);
        assertThat(STARTED_AT).isEqualTo(0L);
        assertThat(STOPPING_AT).isGreaterThanOrEqualTo(0L);
        assertThat(STOPPED_AT).isGreaterThanOrEqualTo(STOPPING_AT);
    }
}
