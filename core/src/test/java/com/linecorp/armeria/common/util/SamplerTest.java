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
package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.util.RateLimitingSampler.LessThan10;

class SamplerTest {

    @Test
    void goodOf() {
        // 'always'
        assertThat(Sampler.of("always")).isSameAs(Sampler.always());
        assertThat(Sampler.of(" always ")).isSameAs(Sampler.always());
        assertThat(Sampler.of("true")).isSameAs(Sampler.always());
        assertThat(Sampler.of(" true ")).isSameAs(Sampler.always());

        // 'never'
        assertThat(Sampler.of("never")).isSameAs(Sampler.never());
        assertThat(Sampler.of(" never ")).isSameAs(Sampler.never());
        assertThat(Sampler.of("false")).isSameAs(Sampler.never());
        assertThat(Sampler.of(" false ")).isSameAs(Sampler.never());

        // 'random=<probability>'
        assertThat(Sampler.of("random=0")).isSameAs(Sampler.never());
        assertThat(Sampler.of("random=1")).isSameAs(Sampler.always());
        assertThat(Sampler.of("random=0.1")).isInstanceOfSatisfying(CountingSampler.class, sampler -> {
            assertThat(sampler.sampleDecisions.cardinality()).isEqualTo(10);
        });
        assertThat(Sampler.of("random=0.1f")).isInstanceOfSatisfying(CountingSampler.class, sampler -> {
            assertThat(sampler.sampleDecisions.cardinality()).isEqualTo(10);
        });
        assertThat(Sampler.of("random=0.01")).isInstanceOfSatisfying(CountingSampler.class, sampler -> {
            assertThat(sampler.sampleDecisions.cardinality()).isOne();
        });
        assertThat(Sampler.of("random=0.01f")).isInstanceOfSatisfying(CountingSampler.class, sampler -> {
            assertThat(sampler.sampleDecisions.cardinality()).isOne();
        });

        // 'rate-limit=<samples_per_sec>'
        Stream.of("rate-limit=0", "rate-limited=0", "rate-limiting=0").forEach(spec -> {
            assertThat(Sampler.of(spec)).isSameAs(Sampler.never());
        });

        Stream.of("rate-limit=1", "rate-limited=1", "rate-limiting=1").forEach(spec -> {
            assertThat(Sampler.of(spec)).isInstanceOfSatisfying(RateLimitingSampler.class, sampler -> {
                assertThat(sampler.maxFunction).isInstanceOfSatisfying(LessThan10.class, maxFunc -> {
                    assertThat(maxFunc.samplesPerSecond).isEqualTo(1);
                });
            });
        });
    }

    @Test
    void badOf() {
        assertThatThrownBy(() -> Sampler.of("foo")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("foo=")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("foo=bar")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("random")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("random=")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("random=x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("rate-limit")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("rate-limit=")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("rate-limit=x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("rate-limited")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("rate-limited=")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("rate-limited=x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("rate-limiting")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("rate-limiting=")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sampler.of("rate-limiting=x")).isInstanceOf(IllegalArgumentException.class);
    }
}
