/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.util;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.epoll.Epoll;
import io.netty.handler.ssl.OpenSsl;

/**
 * Reports the availability of the native libraries used by Armeria.
 */
public final class NativeLibraries {

    private static final Logger logger = LoggerFactory.getLogger(NativeLibraries.class);
    private static final AtomicBoolean reported = new AtomicBoolean();

    private static final boolean USE_EPOLL =
            !"false".equals(System.getProperty("com.linecorp.armeria.useEpoll", "true"));

    private static final boolean USE_OPENSSL =
            !"false".equals(System.getProperty("com.linecorp.armeria.useOpenSsl", "true"));

    /**
     * Logs the availability of the native libraries used by Armeria. This method does nothing if it was
     * called once before.
     */
    public static void report() {
        if (!reported.compareAndSet(false, true)) {
            return;
        }

        if (USE_EPOLL) {
            logger.info("/dev/epoll: " +
                        (Epoll.isAvailable() ? "yes"
                                             : "no (" + filterCause(Epoll.unavailabilityCause()) + ')'));
        } else {
            logger.info("/dev/epoll: disabled");
        }

        if (USE_OPENSSL) {
            logger.info("OpenSSL: " +
                        (OpenSsl.isAvailable() ? "yes (" + OpenSsl.versionString() + ", " +
                                                           OpenSsl.version() + ')'
                                               : "no (" + filterCause(OpenSsl.unavailabilityCause()) + ')'));
        } else {
            logger.info("OpenSSL: disabled");
        }
    }

    private static Throwable filterCause(Throwable cause) {
        if (cause instanceof ExceptionInInitializerError) {
            return cause.getCause();
        }

        return cause;
    }

    /**
     * Returns {@code true} if JNI-based {@code /dev/epoll} transport is available.
     */
    public static boolean isEpollAvailable() {
        report();
        return USE_EPOLL && Epoll.isAvailable();
    }

    /**
     * Returns {@code true} if JNI-based OpenSSL/BoringSSL/LibreSSL transport is available.
     */
    public static boolean isOpenSslAvailable() {
        report();
        return USE_OPENSSL && OpenSsl.isAvailable();
    }

    private NativeLibraries() {}
}
