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

    @Test
    void andOr() {
        final Sampler<Object> alwaysAndAlwaysSampler = Sampler.always().and(Sampler.always());
        for (int i = 0; i < 10; i++) {
            assertThat(alwaysAndAlwaysSampler.isSampled(i)).isTrue();
        }

        final Sampler<Object> alwaysOrAlwaysSampler = Sampler.always().or(Sampler.always());
        for (int i = 0; i < 10; i++) {
            assertThat(alwaysOrAlwaysSampler.isSampled(i)).isTrue();
        }

        final Sampler<Object> neverOrAlwaysSampler = Sampler.never().or(Sampler.always());
        for (int i = 0; i < 10; i++) {
            assertThat(neverOrAlwaysSampler.isSampled(i)).isTrue();
        }

        final Sampler<Object> neverAndAlwaysSampler = Sampler.never().and(Sampler.always());
        for (int i = 0; i < 10; i++) {
            assertThat(neverAndAlwaysSampler.isSampled(i)).isFalse();
        }

        final Sampler<Object> halfAndHalfSampler = Sampler.random(0.5f).and(Sampler.random(0.5f));
        int halfAndHalfSamplerCount = 0;
        for (int i = 0; i < 10000; i++) {
            if (halfAndHalfSampler.isSampled(i)) {
                halfAndHalfSamplerCount += 1;
            }
        }
        // 0.5*0.5 = 0.25
        assertThat(halfAndHalfSamplerCount).isBetween(2000, 3000); // Should be roughly 2500 //

        final Sampler<Object> halfOrHalfSampler = Sampler.random(0.5f).or(Sampler.random(0.5f));
        int halfOrHalfSamplerCount = 0;
        for (int i = 0; i < 10000; i++) {
            if (halfOrHalfSampler.isSampled(i)) {
                halfOrHalfSamplerCount += 1;
            }
        }
        // 1 - (0.5*0.5) = 0.75
        assertThat(halfOrHalfSamplerCount).isBetween(7000, 8000); // Should be roughly 7500
    }

    private static class SampleOnce implements Sampler<Object> {
        int count;

        @Override
        public boolean isSampled(Object object) {
            return count++ == 0;
        }

        public void reset() {
            count = 0;
        }
    }

    @Test
    void andOrNotShortCircuited() {
        final SampleOnce first = new SampleOnce();
        final SampleOnce second = new SampleOnce();

        assertThat(first.and(second).isSampled(0)).isTrue();
        assertThat(first.isSampled(0)).isFalse();
        assertThat(second.isSampled(0)).isFalse();

        first.reset();
        second.reset();

        assertThat(first.or(second).isSampled(0)).isTrue();
        assertThat(first.isSampled(0)).isFalse();
        assertThat(second.isSampled(0)).isFalse();

        first.reset();
        second.reset();

        assertThat(second.and(second).isSampled(0)).isFalse();
    }

    @Test
    void compare() {
        final Sampler<Integer> greaterThanOne = Sampler.greaterThan(1);
        assertThat(greaterThanOne.isSampled(0)).isFalse();
        assertThat(greaterThanOne.isSampled(1)).isFalse();
        assertThat(greaterThanOne.isSampled(2)).isTrue();

        final Sampler<Integer> greaterThanOrEqualOne = Sampler.greaterThanOrEqual(1);
        assertThat(greaterThanOrEqualOne.isSampled(0)).isFalse();
        assertThat(greaterThanOrEqualOne.isSampled(1)).isTrue();
        assertThat(greaterThanOrEqualOne.isSampled(2)).isTrue();

        final Sampler<Integer> lessThanOne = Sampler.lessThan(1);
        assertThat(lessThanOne.isSampled(0)).isTrue();
        assertThat(lessThanOne.isSampled(1)).isFalse();
        assertThat(lessThanOne.isSampled(2)).isFalse();

        final Sampler<Integer> lessThanOrEqualOne = Sampler.lessThanOrEqual(1);
        assertThat(lessThanOrEqualOne.isSampled(0)).isTrue();
        assertThat(lessThanOrEqualOne.isSampled(1)).isTrue();
        assertThat(lessThanOrEqualOne.isSampled(2)).isFalse();

        final Sampler<Integer> equalOne = Sampler.equal(1);
        assertThat(equalOne.isSampled(0)).isFalse();
        assertThat(equalOne.isSampled(1)).isTrue();
        assertThat(equalOne.isSampled(2)).isFalse();
    }
}
