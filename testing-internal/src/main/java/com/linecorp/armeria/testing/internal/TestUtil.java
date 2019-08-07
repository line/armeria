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

package com.linecorp.armeria.testing.internal;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.management.ManagementFactory;
import java.time.Duration;

import org.junit.jupiter.api.function.Executable;

import com.linecorp.armeria.common.util.Exceptions;

public final class TestUtil {

    private static final boolean isDocServiceDemoMode = "true".equals(
            System.getProperty("com.linecorp.armeria.docServiceDemo"));

    /**
     * Indicates doc service tests should be run on fixed ports to be able to demo or develop DocService.
     */
    public static boolean isDocServiceDemoMode() {
        return isDocServiceDemoMode;
    }

    /**
     * Executes {@code r}, timing it out if not done within 10 seconds. The timeout is not enabled if in an IDE
     * debug mode.
     */
    public static void withTimeout(Executable r) {
        withTimeout(r, Duration.ofSeconds(10));
    }

    /**
     * Executes {@code r}, timing it out if not done by the passing of {@code timeout}. The timeout
     * is not enabled if in an IDE debug mode.
     */
    public static void withTimeout(Executable r, Duration timeout) {
        if (isDebugging()) {
            try {
                r.execute();
            } catch (Throwable t) {
                Exceptions.throwUnsafely(t);
            }
        } else {
            assertTimeoutPreemptively(timeout, r);
        }
    }

    private static boolean isDebugging() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Xdebug");
    }

    private TestUtil() {}
}
