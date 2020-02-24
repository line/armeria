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

package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HttpTimestampSupplierTest {

    private static final Instant TIME0 = Instant.parse("2019-10-18T10:15:30.05Z");
    private static final Instant TIME1 = Instant.parse("2019-10-18T10:15:31.25Z");

    @Mock
    Supplier<Instant> instantSupplier;

    @Mock
    LongSupplier nanoTimeSupplier;

    @Test
    void normal() {
        when(nanoTimeSupplier.getAsLong()).thenReturn(TimeUnit.MILLISECONDS.toNanos(-500));
        when(instantSupplier.get()).thenReturn(TIME0);

        // On instantiation, the current nano time must be read and cached.
        final HttpTimestampSupplier supplier = new HttpTimestampSupplier(instantSupplier, nanoTimeSupplier);
        verify(instantSupplier, never()).get();
        verify(nanoTimeSupplier, times(1)).getAsLong();
        clearInvocations(instantSupplier, nanoTimeSupplier);

        // On first generation, both the current instant and nano time must be read.
        final String timestamp1 = supplier.currentTimestamp();
        assertThat(timestamp1).isEqualTo("Fri, 18 Oct 2019 10:15:30 GMT");

        verify(instantSupplier, times(1)).get();
        verify(nanoTimeSupplier, times(1)).getAsLong();
        clearInvocations(instantSupplier, nanoTimeSupplier);

        // Advance the current nano time by (950 milliseconds - 1 nanosecond).
        // This time, only the current nano time must be read.
        // Therefore instantSupplier will never be accessed.
        when(nanoTimeSupplier.getAsLong()).thenReturn(TimeUnit.MILLISECONDS.toNanos(-500 + 950) - 1);
        when(instantSupplier.get()).thenReturn(null);
        final String timestamp2 = supplier.currentTimestamp();
        assertThat(timestamp2).isSameAs(timestamp1);

        verify(instantSupplier, never()).get();
        verify(nanoTimeSupplier, times(1)).getAsLong();
        clearInvocations(instantSupplier, nanoTimeSupplier);

        // Advance the current nano time by 1 nanosecond.
        // Then, both the current instant and nano time will be read.
        when(nanoTimeSupplier.getAsLong()).thenReturn(TimeUnit.MILLISECONDS.toNanos(-500 + 950));
        when(instantSupplier.get()).thenReturn(TIME1);
        assertThat(supplier.currentTimestamp()).isEqualTo("Fri, 18 Oct 2019 10:15:31 GMT");
    }
}
