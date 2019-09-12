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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains APIs that are implemented differently based on the version of Java being run.
 */
public abstract class JavaVersionSpecific {

    private static Logger logger = LoggerFactory.getLogger(JavaVersionSpecific.class);

    private static final JavaVersionSpecific CURRENT = CurrentJavaVersionSpecific.get();

    static {
        logger.info("Using version specific APIs from {}", CURRENT.getClass().getSimpleName());
    }

    /**
     * Returns the {@link JavaVersionSpecific} for the current version of Java.
     */
    public static JavaVersionSpecific get() {
        return CURRENT;
    }

    /**
     * Returns the number of microseconds since the epoch (00:00:00, 01-Jan-1970, GMT).
     */
    public abstract long currentTimeMicros();
}
