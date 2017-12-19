/*
 * Copyright 2016 LINE Corporation
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

import javax.net.ssl.SSLEngine;

import com.linecorp.armeria.common.Flags;

/**
 * Reports the availability of the native libraries used by Armeria.
 *
 * @deprecated Use {@link Flags} instead.
 */
@Deprecated
public final class NativeLibraries {

    /**
     * This method does nothing.
     *
     * @deprecated This method will be removed without a replacement, because the information about
     *             the availability of the native libraries are now logged automatically by {@link Flags}.
     */
    @Deprecated
    public static void report() {}

    /**
     * Returns whether the JNI-based {@code /dev/epoll} socket I/O is enabled. When enabled on Linux, Armeria
     * uses {@code /dev/epoll} directly for socket I/O. When disabled, {@code java.nio} socket API is used
     * instead.
     *
     * @deprecated Use {@link Flags#useEpoll()} instead.
     */
    @Deprecated
    public static boolean isEpollAvailable() {
        return Flags.useEpoll();
    }

    /**
     * Returns whether the JNI-based TLS support with OpenSSL is enabled. When enabled, Armeria uses OpenSSL
     * for processing TLS connections. When disabled, the current JVM's default {@link SSLEngine} is used
     * instead.
     *
     * @deprecated Use {@link Flags#useOpenSsl()} instead.
     */
    @Deprecated
    public static boolean isOpenSslAvailable() {
        return Flags.useOpenSsl();
    }

    private NativeLibraries() {}
}
