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
 *
 * Copyright 2013 <kristofa@github.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.util;

import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.SlidingTimeWindowReservoir;

final class SlidingWindowPercentileSampler implements Sampler<Long> {

    private final float percentile;
    private final long windowLengthMillis;

    private final Histogram histogram;

    SlidingWindowPercentileSampler(float percentile, long windowLengthMillis) {
        this.percentile = percentile;
        this.windowLengthMillis = windowLengthMillis;

        // TODO: Check memory footprint, try limiting resources.
        SlidingTimeWindowReservoir reservoir = new SlidingTimeWindowReservoir(windowLengthMillis,
                                                                              TimeUnit.MILLISECONDS);
        this.histogram = new Histogram(reservoir);
    }

    static SlidingWindowPercentileSampler create(float percentile, long windowLengthMillis) {
        return new SlidingWindowPercentileSampler(percentile, windowLengthMillis);
    }

    @Override
    public boolean isSampled(Long t) {
        histogram.update(t);
        // TODO: get snapshot calls might be expensive, consider caching snapshot
        return histogram.getSnapshot().getValue(this.percentile) <= t;
    }

    @Override
    public String toString() {
        return "SlidingWindowPercentileSampler with " + windowLengthMillis + " ms window and " + percentile
               + " percentile";
    }
}
