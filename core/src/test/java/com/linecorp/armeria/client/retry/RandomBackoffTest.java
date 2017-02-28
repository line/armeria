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

import java.util.Random;

import org.junit.Test;

public class RandomBackoffTest {
    @Test
    public void nextIntervalMillis() throws Exception {
        Random r = new Random(1);
        Backoff backoff = new RandomBackoff(10, 100, () -> r);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(46);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(13);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(28);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(40);
    }

}
