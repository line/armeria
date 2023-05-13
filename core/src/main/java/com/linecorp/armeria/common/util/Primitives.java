/*
 * Copyright 2023 LINE Corporation
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

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.ByteOrder.nativeOrder;

// Forked from https://github.com/OpenHFT/Zero-Allocation-Hashing/blob/ea/src/main/java/net/openhft/hashing/Primitives.java
public final class Primitives {

    private Primitives() {}

    static final boolean NATIVE_LITTLE_ENDIAN = nativeOrder() == LITTLE_ENDIAN;

    static long unsignedInt(int i) {
        return i & 0xFFFFFFFFL;
    }

    static int unsignedShort(int s) {
        return s & 0xFFFF;
    }

    static int unsignedByte(int b) {
        return b & 0xFF;
    }
}
