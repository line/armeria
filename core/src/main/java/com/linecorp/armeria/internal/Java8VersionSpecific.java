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
 * Implementation of {@link JavaVersionSpecific} using Java 8 APIs.
 */
class Java8VersionSpecific extends JavaVersionSpecific {

    private static boolean JETTY_ALPN_OPTIONAL_OR_AVAILABLE;

    static {
        try {
            // Always use bootstrap class loader.
            Class.forName("sun.security.ssl.ALPNExtension", true, null);
            JETTY_ALPN_OPTIONAL_OR_AVAILABLE = true;
        } catch (Throwable ignore) {
            // alpn-boot was not loaded.
            JETTY_ALPN_OPTIONAL_OR_AVAILABLE = false;
        }
    }

    @Override
    public long currentTimeMicros() {
        return TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis());
    }

    @Override
    public int javaVersion() {
        // This class is only used on Java 8.
        return 8;
    }

    @Override
    public boolean jettyAlpnOptionalOrAvailable() {
        return JETTY_ALPN_OPTIONAL_OR_AVAILABLE;
    }
}
