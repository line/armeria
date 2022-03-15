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
import java.util.Set;
import java.util.function.Predicate;

import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearSystemProperty;
import org.junitpioneer.jupiter.SetSystemProperty;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.server.TransientServiceOption;

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
        assumeThat(System.getProperty("com.linecorp.armeria.useEpoll")).isNull();
        assumeThat(System.getProperty("com.linecorp.armeria.transportType")).isNull();

        assertThat(Flags.transportType()).isEqualTo(TransportType.EPOLL);
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
        assumeThat(System.getProperty("com.linecorp.armeria.useOpenSsl")).isNull();

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

    @Test
    @ClearSystemProperty(key = "com.linecorp.armeria.verboseExceptions")
    void defaultVerboseExceptionSamplerSpec() throws Throwable {
        assertFlags("verboseExceptionSamplerSpec", String.class).isSameAs("rate-limit=10");
        assertThat(Flags.verboseExceptionSampler())
                .usingRecursiveComparison()
                .isEqualTo(new ExceptionSampler(Flags.verboseExceptionSamplerSpec()));
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.verboseExceptions", value = "true")
    void verboseExceptionSampler() {
        assertFlags("verboseExceptionSamplerSpec", String.class).isSameAs("always");
    }

    @Test
    @ClearSystemProperty(key = "com.linecorp.armeria.preferredIpV4Addresses")
    void defaultPreferredIpV4Addresses() {
        assertFlags("preferredIpV4Addresses", Predicate.class).isNull();
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.preferredIpV4Addresses", value = "10.0.0.0/8")
    void preferredIpV4Addresses() {
        assertFlags("preferredIpV4Addresses", Predicate.class).isNotNull();
    }

    @Test
    @ClearSystemProperty(key = "com.linecorp.armeria.transientServiceOptions")
    void defaultTransientServiceOptions() {
        assertFlags("transientServiceOptions", Set.class).isEqualTo(ImmutableSet.of());
    }

    //Todo test fail since cannot compare class with diferrent class loader
    @Disabled
    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.transientServiceOptions", value = "with_tracing")
    void transientServiceOptions() throws ClassNotFoundException {
        assertFlags("transientServiceOptions", Set.class)
                .isEqualTo(Sets.immutableEnumSet(TransientServiceOption.WITH_TRACING));
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultWriteTimeoutMillis", value = "-5")
    void defaultWriteTimeoutMillis() {
        assertFlags("defaultWriteTimeoutMillis", long.class).isEqualTo(DefaultFlags.DEFAULT_WRITE_TIMEOUT_MILLIS);
    }

    @Test
    @ClearSystemProperty(key = "com.linecorp.armeria.defaultUseHttp2Preface")
    void defaultValueOfDefaultUseHttp2Preface() {
        assertFlags("defaultUseHttp2Preface", boolean.class).isEqualTo(true);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultUseHttp2Preface", value = "false")
    void defaultUseHttp2Preface() {
        assertFlags("defaultUseHttp2Preface", boolean.class).isEqualTo(false);
    }

    private static ObjectAssert<Object> assertFlags(String flagsMethod, Class<?> returnClass) {
        try {
            final FlagsClassLoader classLoader = new FlagsClassLoader();
            final Class<?> flags = classLoader.loadClass("com.linecorp.armeria.common.Flags");
            final Lookup lookup = MethodHandles.publicLookup();
            final MethodHandle method  =
                    lookup.findStatic(flags, flagsMethod, MethodType.methodType(returnClass));
            return assertThat(method.invoke());
        } catch (Throwable throwable) {
            // do sneaky throw
            throw new AssertionError("Sneaky throw!!", throwable);
        }
    }

    private static class FlagsClassLoader extends ClassLoader {
        FlagsClassLoader() {
            super(getSystemClassLoader());
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (!name.startsWith("com.linecorp.armeria")) {
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
