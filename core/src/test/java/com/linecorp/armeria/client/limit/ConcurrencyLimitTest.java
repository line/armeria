/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;

class ConcurrencyLimitTest {

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void testConcurrencyLimit() throws InterruptedException {
        final DefaultConcurrencyLimit semaphore = new DefaultConcurrencyLimit(ctx -> true, 2, 1, 100000);
        assertThat(semaphore.availablePermits()).isEqualTo(2);

        final SafeCloseable acquired1 = semaphore.acquire(ctx).join();
        assertThat(semaphore.acquiredPermits()).isEqualTo(1);
        assertThat(semaphore.availablePermits()).isEqualTo(1);

        final SafeCloseable acquired2 = semaphore.acquire(ctx).join();
        assertThat(semaphore.acquiredPermits()).isEqualTo(2);
        assertThat(semaphore.availablePermits()).isEqualTo(0);

        final CompletableFuture<SafeCloseable> acquired3Future = semaphore.acquire(ctx);
        Thread.sleep(200);
        // Parameters are still the same.
        assertThat(acquired3Future.isDone()).isFalse();
        assertThat(semaphore.acquiredPermits()).isEqualTo(2);
        assertThat(semaphore.availablePermits()).isEqualTo(0);

        assertThatThrownBy(() -> semaphore.acquire(ctx).join())
                .hasCauseInstanceOf(ExceedingMaxPendingException.class);

        acquired1.close();
        // acquired3Future is done now.
        acquired3Future.join();
        assertThat(semaphore.acquiredPermits()).isEqualTo(2);
        assertThat(semaphore.availablePermits()).isEqualTo(0);

        acquired2.close();
        assertThat(semaphore.acquiredPermits()).isEqualTo(1);
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }
}
