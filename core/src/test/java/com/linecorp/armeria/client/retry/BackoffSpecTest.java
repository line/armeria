/*
 * Copyright 2017 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Flags;

class BackoffSpecTest {

    @Test
    void defaultBackoffSpec() {
        final BackoffSpec backoffSpec = BackoffSpec.parse(Flags.defaultBackoffSpec());
        assertThat(backoffSpec.initialDelayMillis).isEqualTo(200);
        assertThat(backoffSpec.maxDelayMillis).isEqualTo(10000);
        assertThat(backoffSpec.multiplier).isEqualTo(2.0);
        assertThat(backoffSpec.minJitterRate).isEqualTo(-0.2);
        assertThat(backoffSpec.maxJitterRate).isEqualTo(0.2);
    }

    @Test
    void backoffSpecWithString() {
        final String specExp = "exponential=1000:60000:1.2,jitter=-0.4:0.3,maxAttempts=100";
        final BackoffSpec backoffSpecExp = BackoffSpec.parse(specExp);
        //set by the spec
        assertThat(backoffSpecExp.initialDelayMillis).isEqualTo(1000);
        assertThat(backoffSpecExp.maxDelayMillis).isEqualTo(60000);
        assertThat(backoffSpecExp.multiplier).isEqualTo(1.2);
        assertThat(backoffSpecExp.minJitterRate).isEqualTo(-0.4);
        assertThat(backoffSpecExp.maxJitterRate).isEqualTo(0.3);
        assertThat(backoffSpecExp.maxAttempts).isEqualTo(100);

        assertThat(Backoff.of(specExp).as(ExponentialBackoff.class)).isNotNull();

        final String specFib = "fibonacci=1000:60000,jitter=-0.3:0.2,maxAttempts=100";
        final BackoffSpec backoffSpecFib = BackoffSpec.parse(specFib);
        //set by the spec
        assertThat(backoffSpecFib.initialDelayMillis).isEqualTo(1000);
        assertThat(backoffSpecFib.maxDelayMillis).isEqualTo(60000);
        assertThat(backoffSpecFib.minJitterRate).isEqualTo(-0.3);
        assertThat(backoffSpecFib.maxJitterRate).isEqualTo(0.2);
        assertThat(backoffSpecFib.maxAttempts).isEqualTo(100);

        assertThat(Backoff.of(specFib).as(FibonacciBackoff.class)).isNotNull();
    }

    @Test
    void backoffSpecWithoutBaseOption() {
        final String spec = "jitter=-0.4:0.2,maxAttempts=100";
        final BackoffSpec backoffSpec = BackoffSpec.parse(spec);
        // Set by default values
        assertThat(backoffSpec.initialDelayMillis).isEqualTo(200);
        assertThat(backoffSpec.maxDelayMillis).isEqualTo(10000);
        assertThat(backoffSpec.multiplier).isEqualTo(2.0);

        // Set by the spec
        assertThat(backoffSpec.minJitterRate).isEqualTo(-0.4);
        assertThat(backoffSpec.maxJitterRate).isEqualTo(0.2);
        assertThat(backoffSpec.maxAttempts).isEqualTo(100);

        final Backoff backoff = backoffSpec.build();
        // default base backoff is ExponentialBackoff
        assertThat(backoff.as(ExponentialBackoff.class)).isNotNull();
    }

    @Test
    void backoffSpecWithOnlyBaseOption() {
        final String spec1 = "random=0:1000";
        final BackoffSpec backoffSpec1 = BackoffSpec.parse(spec1);
        // Set by the spec
        assertThat(backoffSpec1.randomMinDelayMillis).isEqualTo(0);
        assertThat(backoffSpec1.randomMaxDelayMillis).isEqualTo(1000);

        // Set by default values
        assertThat(backoffSpec1.multiplier).isEqualTo(2.0);
        assertThat(backoffSpec1.minJitterRate).isEqualTo(-0.2);
        assertThat(backoffSpec1.maxJitterRate).isEqualTo(0.2);

        assertThat(Backoff.of(spec1).as(RandomBackoff.class)).isNotNull();

        final String spec2 = "fixed=1000";
        final BackoffSpec backoffSpec2 = BackoffSpec.parse(spec2);
        // Set by the spec
        assertThat(backoffSpec2.fixedDelayMillis).isEqualTo(1000);

        // Set by default values
        assertThat(backoffSpec2.multiplier).isEqualTo(2.0);
        assertThat(backoffSpec2.minJitterRate).isEqualTo(-0.2);
        assertThat(backoffSpec2.maxJitterRate).isEqualTo(0.2);

        assertThat(Backoff.of(spec2).as(FixedBackoff.class)).isNotNull();
    }

    @Test
    void backoffWithWrongArguments() {
        // wrong negative value
        final String spec1 = "exponential=-1000:60000:2.0";
        assertThatThrownBy(() -> BackoffSpec.parse(spec1)).isInstanceOf(IllegalArgumentException.class);

        // exceed bound
        final String spec2 = "jitter=-0.4:1.000000001";
        assertThatThrownBy(() -> BackoffSpec.parse(spec2)).isInstanceOf(IllegalArgumentException.class);

        // same backoff twice
        final String spec3 = "jitter=-0.4:0.2,maxAttempts=100,jitter=-0.4:0.2";
        assertThatThrownBy(() -> BackoffSpec.parse(spec3)).isInstanceOf(IllegalArgumentException.class);

        // two base backoffs
        final String spec4 = "exponential=1000:60000,fixed=1000";
        assertThatThrownBy(() -> BackoffSpec.parse(spec4)).isInstanceOf(IllegalArgumentException.class);

        // typo key
        final String spec5 = "texponential=1000:60000:2.0";
        assertThatThrownBy(() -> BackoffSpec.parse(spec5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backOffSpecWithOneValue() {
        final String spec = "exponential=100:1000,jitter=-0.4:0.3,maxAttempts=100";
        final BackoffSpec backoffSpec = BackoffSpec.parse(spec);
        //set by the spec
        assertThat(backoffSpec.initialDelayMillis).isEqualTo(100);
        assertThat(backoffSpec.maxDelayMillis).isEqualTo(1000);
        //set by default values
        assertThat(backoffSpec.multiplier).isEqualTo(2.0);
        //set by the spec
        assertThat(backoffSpec.minJitterRate).isEqualTo(-0.4);
        assertThat(backoffSpec.maxJitterRate).isEqualTo(0.3);
        assertThat(backoffSpec.maxAttempts).isEqualTo(100);
    }
}
