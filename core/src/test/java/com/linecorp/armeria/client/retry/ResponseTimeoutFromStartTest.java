/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.ResponseTimeoutMode;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ResponseTimeoutFromStartTest {

    private static final Logger logger = LoggerFactory.getLogger(ResponseTimeoutFromStartTest.class);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                final String delayMillisStr = ctx.queryParam("delayMillis");
                assertThat(delayMillisStr).isNotNull();
                final int delayMillis = Integer.parseInt(delayMillisStr);
                return HttpResponse.delayed(HttpResponse.of(500), Duration.ofMillis(delayMillis));
            });
        }
    };

    @ParameterizedTest
    @CsvSource({
            "0,2500,2000",
            "0,1750,2000",
            "5000,1500,2000",
    })
    void originalResponseTimeoutRespected(long backoffMillis, long attemptMillis, long delayMillis) {
        final long timeoutSeconds = 3;
        final WebClient webClient =
                WebClient.builder(server.httpUri())
                         .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                         .responseTimeoutMode(ResponseTimeoutMode.FROM_START)
                         .decorator(
                                 RetryingClient.builder(RetryRule.builder()
                                                                 .onException()
                                                                 .onServerErrorStatus()
                                                                 .thenBackoff(Backoff.fixed(backoffMillis)))
                                               .responseTimeoutForEachAttempt(Duration.ofMillis(attemptMillis))
                                               .maxTotalAttempts(Integer.MAX_VALUE)
                                               .newDecorator())
                         .build();

        final long prev = System.nanoTime();
        final Throwable throwable = catchThrowable(
                () -> webClient.get("/", QueryParams.of("delayMillis", delayMillis)).aggregate().join());
        assertThat(throwable)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        logger.debug("elapsed time is: {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - prev));

        if (backoffMillis > 0) {
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - prev))
                    .isLessThan(TimeUnit.SECONDS.toMillis(timeoutSeconds));
        } else {
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - prev))
                    .isCloseTo(TimeUnit.SECONDS.toMillis(timeoutSeconds), within(1000L));
        }
    }
}
