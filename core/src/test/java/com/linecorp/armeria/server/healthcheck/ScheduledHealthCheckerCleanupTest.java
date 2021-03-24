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
package com.linecorp.armeria.server.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ScheduledHealthCheckerCleanupTest {
    private static final HealthChecker scheduledHealthChecker =
            HealthChecker.ofFixedRate(CompletableFuture::new, Duration.ofHours(24), 0.0);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hc", HealthCheckService.of(scheduledHealthChecker));
        }
    };

    @Test
    void ensureScheduledHealthCheckerCanceled() {
        assertThat(((ScheduledHealthChecker) scheduledHealthChecker).inScheduledFutures).isNotEmpty();
        server.stop().join();
        assertThat(((ScheduledHealthChecker) scheduledHealthChecker).inScheduledFutures).isEmpty();
    }
}
