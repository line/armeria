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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link JavaVersionSpecific} using Java 8 APIs. In general, this class is only used with
 * Java 8 because we override Java 9+ using a multi-release JAR. But ensure any logic is forwards-compatible on
 * all Java versions because this class may be used outside the multi-release JAR, e.g., in testing or when a
 * user shades without creating their own multi-release JAR.
 */
class Java8VersionSpecific extends JavaVersionSpecific {

    private static final Logger logger = LoggerFactory.getLogger(Java8VersionSpecific.class);

    private static final int JAVA_VERSION;

    private static boolean JETTY_ALPN_OPTIONAL_OR_AVAILABLE;

    static {
        //
        int javaVersion = -1;
        try {
            final String spec = System.getProperty("java.specification.version");
            if (spec != null) {
                final String[] strValues = spec.split("\\.");
                final int major;
                final int minor;

                switch (strValues.length) {
                    case 0:
                        major = 0;
                        minor = 0;
                        break;
                    case 1:
                        major = Integer.parseInt(strValues[0]);
                        minor = 0;
                        break;
                    default:
                        major = Integer.parseInt(strValues[0]);
                        minor = Integer.parseInt(strValues[1]);
                }

                if (major > 1) {
                    javaVersion = major;
                } else if (major == 1) {
                    if (minor == 0) {
                        javaVersion = 1;
                    } else if (minor > 0) {
                        javaVersion = minor;
                    }
                }
            }

            if (javaVersion > 0) {
                logger.debug("Java version: {}", javaVersion);
            } else {
                logger.warn("'java.specification.version' contains an unexpected value: {}", spec);
            }
        } catch (Throwable t) {
            logger.warn("Failed to determine Java version", t);
        }

        JAVA_VERSION = javaVersion > 0 ? javaVersion : 8;

        // ALPN check from https://github.com/netty/netty/blob/1065e0f26e0d47a67c479b0fad81efab5d9438d9/handler/src/main/java/io/netty/handler/ssl/JettyAlpnSslEngine.java
        if (JAVA_VERSION >= 9) {
            JETTY_ALPN_OPTIONAL_OR_AVAILABLE = true;
        } else {
            try {
                // Always use bootstrap class loader.
                Class.forName("sun.security.ssl.ALPNExtension", true, null);
                JETTY_ALPN_OPTIONAL_OR_AVAILABLE = true;
            } catch (Throwable ignore) {
                // alpn-boot was not loaded.
                JETTY_ALPN_OPTIONAL_OR_AVAILABLE = false;
            }
        }
    }

    @Override
    public long currentTimeMicros() {
        return TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis());
    }

    @Override
    public int javaVersion() {
        return JAVA_VERSION;
    }

    @Override
    public boolean jettyAlpnOptionalOrAvailable() {
        return JETTY_ALPN_OPTIONAL_OR_AVAILABLE;
    }
}
