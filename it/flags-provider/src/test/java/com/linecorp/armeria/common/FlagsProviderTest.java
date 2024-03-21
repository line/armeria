/*
 * Copyright 2022 LINE Corporation
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
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;

import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearSystemProperty;
import org.junitpioneer.jupiter.SetSystemProperty;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.InetAddressPredicates;

import io.micrometer.core.instrument.Metrics;

@SetSystemProperty(
        key = "com.linecorp.armeria.requestContextStorageProvider",
        value = "com.linecorp.armeria.common.Custom2RequestContextStorageProvider"
)
class FlagsProviderTest {

    private Class<?> flags;

    @BeforeEach
    void reloadFlags() throws ClassNotFoundException {
        final FlagsClassLoader classLoader = new FlagsClassLoader();
        flags = classLoader.loadClass(Flags.class.getCanonicalName());
    }

    @Test
    void overrideDefaultFlagsProvider() throws Throwable {
        assertFlags("useOpenSsl").isEqualTo(false);
        assertFlags("numCommonBlockingTaskThreads").isEqualTo(100);
    }

    @Test
    void useDefaultGivenFlagIsNotOverride() throws Throwable {
        final int defaultDefaultHttp2MaxFrameSize = 16384;
        assertFlags("defaultHttp2MaxFrameSize").isEqualTo(defaultDefaultHttp2MaxFrameSize);
    }

    @Test
    void spiInvalidFallbackToDefault() throws Throwable {
        final long defaultDefaultRequestTimeoutMillis = 10 * 1000;
        assertFlags("defaultRequestTimeoutMillis")
                .isEqualTo(defaultDefaultRequestTimeoutMillis);
        final String defaultDefaultBackoffSpec = "exponential=200:10000,jitter=0.2";
        assertFlags("defaultBackoffSpec")
                .isEqualTo(defaultDefaultBackoffSpec);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultMaxTotalAttempts", value = "-5")
    void systemPropertyProviderInvalidFallbackToNextSpi() throws Throwable {
        assertFlags("defaultMaxTotalAttempts").isEqualTo(5);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultMaxClientConnectionAgeMillis", value = "20")
    void systemPropertyProviderPriorityHigherThanSpi() throws Throwable {
        assertFlags("defaultMaxClientConnectionAgeMillis").isEqualTo(20L);
    }

    @Test
    void useHigherPriorityFlag() throws Throwable {
        assertFlags("defaultServerConnectionDrainDurationMicros").isEqualTo(1000L);
    }

    @Test
    void useLowerPriorityFlagWhenHigherPriorityFlagInputIsInvalid() throws Throwable {
        assertFlags("maxNumConnections").isEqualTo(20);
    }

    @Test
    void nullAbleCacheSpecFlagOffValue() throws Throwable {
        assertFlags("routeCacheSpec").isNull();
    }

    @Test
    void overrideNullableFlag() throws Throwable {
        assertFlags("headerValueCacheSpec").isEqualTo("maximumSize=4096,expireAfterAccess=600s");
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.requestContextStorageProvider",
            value = "com.linecorp.armeria.common.Custom1RequestContextStorageProvider")
    void twoRequestContextStorageProvidersAreProvidedAndCorrectFQCNisSpecify() throws Throwable {
        final Method method = flags.getDeclaredMethod("requestContextStorageProvider");
        final String actual = method.invoke(flags).getClass().getSimpleName();
        assertThat(actual).isEqualTo(Custom1RequestContextStorageProvider.class.getSimpleName());
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.requestContextStorageProvider",
            value = "com.linecorp.armeria.common.InvalidRequestContextStorageProvider")
    void twoRequestContextStorageProvidersAreProvidedAndInvalidFQCNisSpecify() throws Throwable {
        final Method method = flags.getDeclaredMethod("requestContextStorageProvider");
        final String actual = method.invoke(flags).getClass().getSimpleName();
        assertThat(actual).isEqualTo(Custom1RequestContextStorageProvider.class.getSimpleName());
    }

    @Test
    void twoRequestContextStorageProvidersAreProvidedButNoFQCNisSpecify() throws Throwable {
        assumeThat(System.getProperty("com.linecorp.armeria.requestContextStorageProvider")).isNull();

        final RequestContextStorage actual = Flags.requestContextStorageProvider().newStorage();
        assertThat(actual).isEqualTo(RequestContextStorage.threadLocal());
    }

    @Test
    @ClearSystemProperty(key = "com.linecorp.armeria.preferredIpV4Addresses")
    void nullableValueOfDefaultPreferredIpV4Addresses() throws Throwable {
        assertFlags("preferredIpV4Addresses")
                .usingRecursiveComparison()
                .isEqualTo(InetAddressPredicates.ofCidr("211.111.111.111"));
    }

    @Test
    void testMeterRegistry() {
        assertThat(Flags.meterRegistry()).isNotSameAs(Metrics.globalRegistry);
    }

    private ObjectAssert<Object> assertFlags(String flagsMethod) throws Throwable {
        final Lookup lookup = MethodHandles.publicLookup();
        final MethodHandle method =
                lookup.findStatic(flags, flagsMethod, MethodType.methodType(
                        Flags.class.getMethod(flagsMethod).getReturnType()));
        return assertThat(method.invoke());
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

            // Reload every class in armeria package.
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
