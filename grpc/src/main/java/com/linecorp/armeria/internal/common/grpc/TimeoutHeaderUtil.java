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
/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.linecorp.armeria.internal.common.grpc;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.TimeUnit;

/**
 * Marshals a nanoseconds representation of the timeout to and from a string representation,
 * consisting of an ASCII decimal representation of a number with at most 8 digits, followed by a
 * unit.
 * <ul>
 *   <li>n = nanoseconds</li>
 *   <li>u = microseconds</li>
 *   <li>m = milliseconds</li>
 *   <li>S = seconds</li>
 *   <li>M = minutes</li>
 *   <li>H = hours</li>
 * </ul>
 *
 * <p>The representation is greedy with respect to precision. That is, 2 seconds will be
 * represented as `2000000u`.</p>
 *
 * <p>See <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#requests">the
 * request header definition</a></p>
 */
public final class TimeoutHeaderUtil {

    /**
     * Serialize the given timeout to a {@link String}.
     */
    public static String toHeaderValue(long timeoutNanos) {
        final long cutoff = 100000000;
        if (timeoutNanos < 0) {
            throw new IllegalArgumentException("Timeout too small");
        } else if (timeoutNanos < cutoff) {
            return TimeUnit.NANOSECONDS.toNanos(timeoutNanos) + "n";
        } else if (timeoutNanos < cutoff * 1000L) {
            return TimeUnit.NANOSECONDS.toMicros(timeoutNanos) + "u";
        } else if (timeoutNanos < cutoff * 1000L * 1000L) {
            return TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + "m";
        } else if (timeoutNanos < cutoff * 1000L * 1000L * 1000L) {
            return TimeUnit.NANOSECONDS.toSeconds(timeoutNanos) + "S";
        } else if (timeoutNanos < cutoff * 1000L * 1000L * 1000L * 60L) {
            return TimeUnit.NANOSECONDS.toMinutes(timeoutNanos) + "M";
        } else {
            return TimeUnit.NANOSECONDS.toHours(timeoutNanos) + "H";
        }
    }

    /**
     * Parse the timeout from the {@link String}.
     */
    public static long fromHeaderValue(String serialized) {
        checkArgument(!serialized.isEmpty(), "empty timeout");
        checkArgument(serialized.length() <= 9, "bad timeout format");
        final long value = Long.parseLong(serialized.substring(0, serialized.length() - 1));
        final char unit = serialized.charAt(serialized.length() - 1);
        switch (unit) {
            case 'n':
                return TimeUnit.NANOSECONDS.toNanos(value);
            case 'u':
                return TimeUnit.MICROSECONDS.toNanos(value);
            case 'm':
                return TimeUnit.MILLISECONDS.toNanos(value);
            case 'S':
                return TimeUnit.SECONDS.toNanos(value);
            case 'M':
                return TimeUnit.MINUTES.toNanos(value);
            case 'H':
                return TimeUnit.HOURS.toNanos(value);
            default:
                throw new IllegalArgumentException(String.format("Invalid timeout unit: %s", unit));
        }
    }

    private TimeoutHeaderUtil() {}
}
