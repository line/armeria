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

package com.linecorp.armeria.internal.metric;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Java6Assertions.assertThat;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry;
import io.micrometer.core.instrument.util.MeterId;

public class MicrometerUtilTest {

    private static final MeterId ID_A = new MeterId("a", ImmutableList.of());
    private static final MeterRegistry metrics = new PrometheusMeterRegistry();

    @Test
    public void getOrCreateGroup() {
        final Integer a = MicrometerUtil.register(metrics, ID_A, Integer.class,
                                                 (parent, id) -> 42);

        assertThat(MicrometerUtil.register(metrics, ID_A, Integer.class,
                                           (parent, id) -> 0)).isSameAs(a);

        // Type mismatches.
        assertThatThrownBy(() -> MicrometerUtil.register(metrics, ID_A, String.class,
                                                         (parent, id) -> "foo"))
                .isInstanceOf(IllegalStateException.class);
    }
}
