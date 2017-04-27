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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;

import org.junit.Test;

public class RandomBackoffTest {
    @Test
    public void nextIntervalMillis() throws Exception {
        Random r = new Random(1);
        Backoff backoff = new RandomBackoff(10, 100, () -> r);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(18);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(93);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(12);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(95);
    }

    @Test
    public void validation() {
        // Negative minIntervalMillis
        assertThatThrownBy(() -> new RandomBackoff(-1, 1, Random::new))
                .isInstanceOf(IllegalArgumentException.class);

        // minIntervalMillis > maxIntervalMillis
        assertThatThrownBy(() -> new RandomBackoff(2, 1, Random::new))
                .isInstanceOf(IllegalArgumentException.class);

        // Should not fail for 0-interval:
        new RandomBackoff(0, 0, Random::new);
    }
}
