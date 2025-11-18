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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.EnumMap;

public class SettableXdsRandom implements XdsRandom {

    private final EnumMap<RandomHint, Integer> nextIntOverrides = new EnumMap<>(RandomHint.class);
    private final EnumMap<RandomHint, Long> nextLongOverrides = new EnumMap<>(RandomHint.class);

    void fixNextInt(RandomHint randomHint, int value) {
        nextIntOverrides.put(randomHint, value);
    }

    void fixNextLong(RandomHint randomHint, long value) {
        nextLongOverrides.put(randomHint, value);
    }

    @Override
    public int nextInt(int bound, RandomHint randomHint) {
        if (nextIntOverrides.containsKey(randomHint)) {
            final int ret = nextIntOverrides.get(randomHint);
            assert ret < bound;
            return ret;
        }
        return XdsRandom.super.nextInt(bound, randomHint);
    }

    @Override
    public long nextLong(long bound, RandomHint randomHint) {
        if (nextLongOverrides.containsKey(randomHint)) {
            final long ret = nextLongOverrides.get(randomHint);
            assert ret < bound;
            return ret;
        }
        return XdsRandom.super.nextLong(bound, randomHint);
    }
}
