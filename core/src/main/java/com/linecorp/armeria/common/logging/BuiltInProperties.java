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
package com.linecorp.armeria.common.logging;

final class BuiltInProperties {

    private long elements;

    void add(BuiltInProperty e) {
        elements |= 1L << e.ordinal();
    }

    boolean contains(BuiltInProperty e) {
        return (elements & mask(e)) != 0;
    }

    private static long mask(BuiltInProperty e) {
        return 1L << e.ordinal();
    }

    private static long mask(BuiltInProperty... elems) {
        long mask = 0L;
        for (BuiltInProperty e : elems) {
            mask |= mask(e);
        }
        return mask;
    }
}
