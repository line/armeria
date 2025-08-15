/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class RefreshingTlsProviderTest {

    @Test
    void shouldRefreshTlsKeyPairPeriodically() throws InterruptedException {
        final TlsKeyPair keyPair = TlsKeyPair.ofSelfSigned();
        final AtomicInteger counter = new AtomicInteger();
        final TlsProvider tlsProvider = TlsProvider.ofScheduled(() -> {
            counter.incrementAndGet();
            return keyPair;
        }, Duration.ofSeconds(1));
        Thread.sleep(2000);
        assertThat(counter.get()).isGreaterThanOrEqualTo(2);
        tlsProvider.close();
    }

    @Test
    void shouldReturnKeyTlsKeyPairOnUpdate() throws InterruptedException {
        final AtomicInteger counter = new AtomicInteger();
        final TlsProvider tlsProvider = TlsProvider.ofScheduled(() -> {
            counter.incrementAndGet();
            return TlsKeyPair.ofSelfSigned();
        }, Duration.ofSeconds(1));
        final TlsKeyPair initialKeyPair = tlsProvider.keyPair("*");
        Thread.sleep(2000);
        final TlsKeyPair newKeyPair = tlsProvider.keyPair("*");
        assertThat(counter.get()).isGreaterThanOrEqualTo(2);
        assertThat(newKeyPair).isNotSameAs(initialKeyPair);
        tlsProvider.close();
    }

    @Test
    void shouldNotifyListenerOnKeyPair() throws InterruptedException {
        final AtomicReference<TlsKeyPair> keyPairRef = new AtomicReference<>();
        keyPairRef.set(TlsKeyPair.ofSelfSigned());

        final AtomicReference<TlsKeyPair> capturedKeyPairRef = new AtomicReference<>();
        final TlsProvider tlsProvider =
                TlsProvider.ofScheduled(() -> keyPairRef.get(),
                                        ImmutableList.of(), capturedKeyPairRef::set,
                                        Duration.ofMillis(100),
                                        CommonPools.blockingTaskExecutor());
        assertThat(capturedKeyPairRef).hasNullValue();
        Thread.sleep(1000);
        assertThat(capturedKeyPairRef).hasNullValue();
        keyPairRef.set(TlsKeyPair.ofSelfSigned());
        await().untilAsserted(() -> {
            assertThat(capturedKeyPairRef).hasValue(keyPairRef.get());
        });
        tlsProvider.close();
    }
}
