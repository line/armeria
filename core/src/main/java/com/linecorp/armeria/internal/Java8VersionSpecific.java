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

import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link JavaVersionSpecific} using Java 8 APIs. In general, this class is only used with
 * Java 8 because we override Java 9+ using a multi-release JAR. But ensure any logic is forwards-compatible on
 * all Java versions because this class may be used outside the multi-release JAR, e.g., in testing or when a
 * user shades without creating their own multi-release JAR.
 */
class Java8VersionSpecific extends JavaVersionSpecific {

    @Override
    public long currentTimeMicros() {
        return TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis());
    }
}
