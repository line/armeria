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
package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Random;

import org.junit.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

public class BackoffTest {
    @Test
    public void withoutDelay() throws Exception {
        Backoff backoff = Backoff.withoutDelay();
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(0);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(0);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(0);
    }

    @Test
    public void fixed() throws Exception {
        Backoff backoff = Backoff.fixed(100);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(100);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(100);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(100);
    }

    @Test
    public void exponential() throws Exception {
        Backoff backoff = Backoff.exponential(10, 50);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(10);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(20);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(40);
        assertThat(backoff.nextIntervalMillis(4)).isEqualTo(50);
        assertThat(backoff.nextIntervalMillis(5)).isEqualTo(50);

        backoff = Backoff.exponential(10, 120, 3.0);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(10);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(30);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(90);
        assertThat(backoff.nextIntervalMillis(4)).isEqualTo(120);
        assertThat(backoff.nextIntervalMillis(5)).isEqualTo(120);
    }

    @Test
    public void withJitter() throws Exception {
        Random random = new Random(1);
        Backoff backoff = Backoff.fixed(100).withJitter(-50, 50, () -> random);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(120);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(139);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(94);
    }

    @Test
    public void withMaxAttempts() throws Exception {
        Backoff backoff = Backoff.fixed(100).withMaxAttempts(2);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(100);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(-1);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(-1);
    }

    @Test
    public void limitRetryAttempts() {
        @SuppressWarnings("unchecked")
        final Client<Request, Response> client = mock(Client.class);
        @SuppressWarnings("unchecked")
        final RetryRequestStrategy<Request, Response> strategy = mock(RetryRequestStrategy.class);
        final Backoff backoff = Backoff.withoutDelay();
        final int maxAttempts = 5;
        RetryingClient<Request, Response> retryingClient =
                new RetryingClient<Request, Response>(client, strategy, () -> backoff, maxAttempts) {
            @Override
            public Response execute(ClientRequestContext ctx, Request req) throws Exception {
                return null; // Not executed.
            }
        };

        final Backoff newBackoff = retryingClient.newBackoff();
        int currentAttemptNo = 1;
        assertThat(newBackoff.nextIntervalMillis(currentAttemptNo++)).isEqualTo(0);
        assertThat(newBackoff.nextIntervalMillis(currentAttemptNo++)).isEqualTo(0);
        assertThat(newBackoff.nextIntervalMillis(currentAttemptNo++)).isEqualTo(0);
        assertThat(newBackoff.nextIntervalMillis(currentAttemptNo++)).isEqualTo(0);

        // After 5 tries which are the sum of first normal try and 4 consecutive retries,
        // it's failed returning -1.
        assertThat(newBackoff.nextIntervalMillis(currentAttemptNo++)).isEqualTo(-1);
    }
}
