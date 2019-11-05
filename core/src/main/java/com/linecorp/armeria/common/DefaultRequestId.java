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
package com.linecorp.armeria.common;

import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

/**
 * The default {@link RequestId} implementation.
 */
final class DefaultRequestId implements RequestId {

    private static final byte[] HEXDIGITS = { '0', '1', '2', '3', '4', '5', '6', '7',
                                              '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    private final long value;

    @Nullable
    private String longText;
    @Nullable
    private String shortText;

    DefaultRequestId() {
        this(ThreadLocalRandom.current().nextLong());
    }

    DefaultRequestId(long value) {
        this.value = value;
    }

    @Override
    public String text() {
        if (longText != null) {
            return longText;
        }

        return longText = newLongText();
    }

    private String newLongText() {
        final String newLongText;
        if (Integer.numberOfLeadingZeros((int) (value >>> 32)) < 4) {
            // Will hit this branch at the chance of 15/16 (93.75%).
            newLongText = Long.toHexString(value);
        } else {
            newLongText = newTextSlow(value, 16);
        }
        return newLongText;
    }

    @Override
    public String shortText() {
        if (shortText != null) {
            return shortText;
        }

        return shortText = newShortText();
    }

    private String newShortText() {
        final int value = (int) (this.value >>> 32);
        final String newShortText;
        if (Integer.numberOfLeadingZeros(value) < 4) {
            newShortText = Integer.toHexString(value);
        } else {
            newShortText = newTextSlow(value, 8);
        }
        return newShortText;
    }

    @SuppressWarnings("deprecation")
    private static String newTextSlow(long value, int digits) {
        final byte[] bytes = new byte[digits];
        for (int i = digits - 1; i >= 0; i--) {
            bytes[i] = HEXDIGITS[(int) value & 0x0F];
            value >>>= 4;
        }
        return new String(bytes, 0);
    }

    @Override
    public int hashCode() {
        return (int) value;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DefaultRequestId)) {
            return false;
        }

        final DefaultRequestId that = (DefaultRequestId) obj;
        return value == that.value;
    }

    @Override
    public String toString() {
        return text();
    }
}
