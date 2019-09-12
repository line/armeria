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

package com.linecorp.armeria.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link JavaVersionSpecific} using Java 9 APIs.
 */
class Java9VersionSpecific extends Java8VersionSpecific {

    @Override
    public final long currentTimeMicros() {
        final Instant now = Clock.systemUTC().instant();
        return TimeUnit.SECONDS.toMicros(now.getEpochSecond()) + TimeUnit.NANOSECONDS.toMicros(
                now.getNano());
    }

    @Override
    public final int javaVersion() {
        // Deprecated on 10+, not on 9.
        return Runtime.version().major();
    }

    @Override
    public boolean jettyAlpnOptionalOrAvailable() {
        // Always optional on 9+.
        return true;
    }
}
