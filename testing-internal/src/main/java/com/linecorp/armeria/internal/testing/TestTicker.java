/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.testing;

import java.time.Duration;

import com.linecorp.armeria.common.util.Ticker;

public final class TestTicker implements Ticker {

    long nanoTime;

    public void reset(long nanoTime) {
        this.nanoTime = nanoTime;
    }

    public void advance(long durationNanos) {
        nanoTime += durationNanos;
    }

    public void advance(Duration duration) {
        advance(duration.toNanos());
    }

    @Override
    public long read() {
        return nanoTime;
    }
}
