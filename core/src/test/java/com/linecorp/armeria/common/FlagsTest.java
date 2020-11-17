/*
 * Copyright 2018 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLConnection;

import org.junit.jupiter.api.Test;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.util.TransportType;

import io.netty.channel.epoll.Epoll;
import io.netty.handler.ssl.OpenSsl;

class FlagsTest {

    private static final String osName = Ascii.toLowerCase(System.getProperty("os.name"));

    /**
     * Makes sure /dev/epoll is used while running tests on Linux.
     */
    @Test
    void epollAvailableOnLinux() {
        assumeThat(osName).startsWith("linux");
        assumeThat(System.getenv("WSLENV")).isNull();
        assumeThat(System.getProperty("com.linecorp.armeria.useEpoll")).isEqualTo("false");

        assertThat(Flags.defaultTransportType()).isEqualTo(TransportType.EPOLL);
        assertThat(Epoll.isAvailable()).isTrue();
    }

    /**
     * Makes sure OpenSSL SSLEngine is used instead of JDK SSLEngine while running tests
     * on Linux, Windows and OS X.
     */
    @Test
    void openSslAvailable() {
        assumeThat(osName.startsWith("linux") || osName.startsWith("windows") ||
                   osName.startsWith("macosx") || osName.startsWith("osx")).isTrue();
        assumeThat(System.getProperty("com.linecorp.armeria.useOpenSsl")).isEqualTo("false");

        assertThat(Flags.useOpenSsl()).isTrue();
        assertThat(OpenSsl.isAvailable()).isTrue();
    }

    @Test
    void dumpOpenSslInfoDoNotThrowStackOverFlowError() throws Throwable {
        assumeThat(OpenSsl.isAvailable()).isTrue();
        System.setProperty("com.linecorp.armeria.dumpOpenSslInfo", "true");

        // There's a chance that Flags.useOpenSsl() is already called by other test cases, which means that
        // we cannot set dumpOpenSslInfo. So we use our own class loader to load the Flags class.
        final FlagsClassLoader classLoader = new FlagsClassLoader();
        final Class<?> flags = classLoader.loadClass("com.linecorp.armeria.common.Flags");
        final Lookup lookup = MethodHandles.publicLookup();
        final MethodHandle useOpenSslMethodHandle = lookup.findStatic(flags, "useOpenSsl",
                                                                      MethodType.methodType(boolean.class));
        useOpenSslMethodHandle.invoke(); // Call Flags.useOpenSsl();

        final MethodHandle dumpOpenSslInfoMethodHandle =
                lookup.findStatic(flags, "dumpOpenSslInfo", MethodType.methodType(boolean.class));
        // // Call Flags.dumpOpenSslInfo();
        assertThat(dumpOpenSslInfoMethodHandle.invoke()).isSameAs(Boolean.TRUE);
    }

    private static class FlagsClassLoader extends ClassLoader {
        FlagsClassLoader() {
            super(getSystemClassLoader());
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (!name.startsWith("com.linecorp.armeria.common")) {
                return super.loadClass(name);
            }

            // Reload every class in common package.
            try {
                // Classes do not have an inner class.
                final String replaced = name.replace('.', '/') + ".class";
                final URL url = getClass().getClassLoader().getResource(replaced);
                final URLConnection connection = url.openConnection();
                final InputStream input = connection.getInputStream();
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int data = input.read();

                while (data != -1) {
                    buffer.write(data);
                    data = input.read();
                }

                input.close();
                final byte[] classData = buffer.toByteArray();

                return defineClass(name, classData, 0, classData.length);
            } catch (IOException e) {
                Exceptions.throwUnsafely(e);
            }
            return null;
        }
    }
}
