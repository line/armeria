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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
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
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearSystemProperty;
import org.junitpioneer.jupiter.SetSystemProperty;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.server.MultipartRemovalStrategy;
import com.linecorp.armeria.server.TransientServiceOption;

import io.netty.channel.epoll.Epoll;
import io.netty.handler.ssl.OpenSsl;

class FlagsTest {

    private static final String osName = Ascii.toLowerCase(System.getProperty("os.name"));
    private FlagsClassLoader flagsClassLoader;
    private Class<?> flags;

    @BeforeEach
    void reloadFlags() throws ClassNotFoundException {
        flagsClassLoader = new FlagsClassLoader();
        flags = flagsClassLoader.loadClass(Flags.class.getCanonicalName());
    }

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
                   osName.startsWith("mac") || osName.startsWith("osx")).isTrue();
        assumeThat(System.getProperty("com.linecorp.armeria.tlsEngineType")).isNull();

        assertThat(Flags.tlsEngineType()).isEqualTo(TlsEngineType.OPENSSL);
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
        // Call Flags.dumpOpenSslInfo();
        assertThat(dumpOpenSslInfoMethodHandle.invoke()).isSameAs(Boolean.TRUE);
    }

    @Test
    void defaultTlsEngineType() {
        assumeThat(System.getProperty("com.linecorp.armeria.tlsEngineType")).isNull();

        assertThat(Flags.tlsEngineType()).isEqualTo(TlsEngineType.OPENSSL);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.useOpenSsl", value = "false")
    @SetSystemProperty(key = "com.linecorp.armeria.tlsEngineType", value = "OPENSSL")
    void tlsEngineTypeIsUsedWhenIncompatibleWithUseOpenSsl() {
        assertThat(Flags.tlsEngineType()).isEqualTo(TlsEngineType.OPENSSL);
    }

    @Test
    void defaultRequestContextStorageProvider() throws Throwable {
        assumeThat(System.getProperty("com.linecorp.armeria.requestContextStorageProvider")).isNull();

        final RequestContextStorage actual = Flags.requestContextStorageProvider().newStorage();
        assertThat(actual).isEqualTo(RequestContextStorage.threadLocal());
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.preferredIpV4Addresses", value = "10.0.0.0/8")
    void systemPropertyPreferredIpV4Addresses() throws Throwable {
        assertFlags("preferredIpV4Addresses").isNotNull();
    }

    @Test
    @ClearSystemProperty(key = "com.linecorp.armeria.transientServiceOptions")
    void defaultTransientServiceOptions() throws Throwable {
        assertFlags("transientServiceOptions").isEqualTo(ImmutableSet.of());
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.transientServiceOptions", value = "with_tracing")
    void systemPropertyTransientServiceOptions() throws Throwable {
        assertFlags("transientServiceOptions")
                .usingRecursiveComparison()
                .isEqualTo(Sets.immutableEnumSet(TransientServiceOption.WITH_TRACING));
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultWriteTimeoutMillis", value = "-5")
    void systemPropertyDefaultWriteTimeoutMillisFailValidation() throws Throwable {
        assertFlags("defaultWriteTimeoutMillis").isEqualTo(1000L);
    }

    @Test
    @ClearSystemProperty(key = "com.linecorp.armeria.defaultUseHttp2Preface")
    void defaultValueOfDefaultUseHttp2Preface() throws Throwable {
        assertFlags("defaultUseHttp2Preface").isEqualTo(true);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultUseHttp2Preface", value = "false")
    void systemPropertyDefaultUseHttp2Preface() throws Throwable {
        assertFlags("defaultUseHttp2Preface").isEqualTo(false);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.useDefaultSocketOptions", value = "falze")
    void invalidBooleanSystemPropertyFlag() throws Throwable {
        assertFlags("useDefaultSocketOptions").isEqualTo(true);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.preferredIpV4Addresses",
                       value = "211.111.111.111,10.0.0.0/8,192.168.1.0/24")
    void preferredIpV4Addresses() throws Throwable {
        final Lookup lookup = MethodHandles.publicLookup();
        final MethodHandle method =
                lookup.findStatic(flags, "preferredIpV4Addresses", MethodType.methodType(
                        Flags.class.getMethod("preferredIpV4Addresses").getReturnType()));
        final Predicate<InetAddress> preferredIpV4Addresses = (Predicate) method.invoke();
        assertThat(preferredIpV4Addresses).accepts(InetAddress.getByName("192.168.1.1"),
                                                   InetAddress.getByName("10.255.255.255"),
                                                   InetAddress.getByName("211.111.111.111"));
        assertThat(preferredIpV4Addresses).rejects(InetAddress.getByName("192.168.2.1"),
                                                   InetAddress.getByName("11.0.0.0"),
                                                   InetAddress.getByName("211.111.111.110"));
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.preferredIpV4Addresses",
                       value = "211.111.111.111,10.0.0.0/40")
    void someOfPreferredIpV4AddressesIsInvalid() throws Throwable {
        // 10.0.0.0/40 is invalid cidr
        final Lookup lookup = MethodHandles.publicLookup();
        final MethodHandle method =
                lookup.findStatic(flags, "preferredIpV4Addresses", MethodType.methodType(
                        Flags.class.getMethod("preferredIpV4Addresses").getReturnType()));
        final Predicate<InetAddress> preferredIpV4Addresses = (Predicate) method.invoke();
        assertThat(preferredIpV4Addresses).accepts(InetAddress.getByName("211.111.111.111"));
        assertThat(preferredIpV4Addresses).rejects(InetAddress.getByName("10.0.0.0"),
                                                   InetAddress.getByName("10.0.0.1"));
    }

    @Test
    @ClearSystemProperty(key = "com.linecorp.armeria.verboseExceptions")
    void defaultVerboseExceptionSamplerSpec() throws Throwable {
        final Method method = flags.getDeclaredMethod("verboseExceptionSampler");
        assertThat(method.invoke(flags))
                .usingRecursiveComparison()
                .isEqualTo(new ExceptionSampler("rate-limit=10"));
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.verboseExceptions", value = "true")
    void systemPropertyVerboseExceptionSampler() throws Throwable {
        final Method method = flags.getDeclaredMethod("verboseExceptionSampler");
        assertThat(method.invoke(flags))
                .usingRecursiveComparison()
                .isEqualTo(new ExceptionSampler("true"));
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.verboseExceptions", value = "invalid-sampler-spec")
    void invalidSystemPropertyVerboseExceptionSampler() throws Throwable {
        final Method method = flags.getDeclaredMethod("verboseExceptionSampler");
        assertThat(method.invoke(flags))
                .usingRecursiveComparison()
                .isEqualTo(new ExceptionSampler("rate-limit=10"));
    }

    @Test
    void defaultRequestContextLeakDetectionSampler() throws Exception {
        final Method method = flags.getDeclaredMethod("requestContextLeakDetectionSampler");
        assertThat(method.invoke(flags))
                .usingRecursiveComparison()
                .isEqualTo(Sampler.never());
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.requestContextLeakDetectionSampler", value = "always")
    void systemPropertyRequestContextLeakDetectionSampler() throws Exception {
        final Method method = flags.getDeclaredMethod("requestContextLeakDetectionSampler");
        assertThat(method.invoke(flags))
                .usingRecursiveComparison()
                .isEqualTo(Sampler.always());
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.requestContextLeakDetectionSampler", value = "invalid-spec")
    void invalidSystemPropertyRequestContextLeakDetectionSampler() throws Exception {
        final Method method = flags.getDeclaredMethod("requestContextLeakDetectionSampler");
        assertThat(method.invoke(flags))
                .usingRecursiveComparison()
                .isEqualTo(Sampler.never());
    }

    @Test
    void defaultMultipartRemovalStrategy() throws Throwable {
        assertThat(Flags.defaultMultipartRemovalStrategy())
                .isEqualTo(MultipartRemovalStrategy.ON_RESPONSE_COMPLETION);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultMultipartRemovalStrategy", value = "NEVER")
    void overrideMultipartRemovalStrategy() throws Throwable {
        final Method method = flags.getDeclaredMethod("defaultMultipartRemovalStrategy");
        assertThat(method.invoke(flags))
                .usingRecursiveComparison()
                .isEqualTo(MultipartRemovalStrategy.NEVER);
    }

    @Test
    void testApiConsistencyBetweenFlagsAndFlagsProvider() {
        //Check method consistency between Flags and FlagsProvider excluding deprecated methods
        final Set<String> flagsApis = Arrays.stream(Flags.class.getMethods())
                                            .filter(m -> !m.isAnnotationPresent(Deprecated.class))
                                            .map(Method::getName)
                                            .collect(Collectors.toSet());
        flagsApis.removeAll(Arrays.stream(Object.class.getMethods())
                                  .map(Method::getName)
                                  .collect(toImmutableSet()));

        final Set<String> armeriaOptionsProviderApis = Arrays.stream(FlagsProvider.class.getMethods())
                                                             .filter(m -> !m.isAnnotationPresent(
                                                                     Deprecated.class))
                                                             .map(Method::getName)
                                                             .collect(Collectors.toSet());
        final Set<String> knownIgnoreMethods = ImmutableSet.of("priority", "name");
        armeriaOptionsProviderApis.removeAll(knownIgnoreMethods);

        assertThat(flagsApis).hasSameElementsAs(armeriaOptionsProviderApis);
    }

    private ObjectAssert<Object> assertFlags(String flagsMethod) throws Throwable {
        final Lookup lookup = MethodHandles.publicLookup();
        Class<?> rtype = Flags.class.getMethod(flagsMethod).getReturnType();
        if (!rtype.isPrimitive()) {
            rtype = flagsClassLoader.loadClass(rtype.getCanonicalName());
        }
        final MethodHandle method =
                lookup.findStatic(flags, flagsMethod, MethodType.methodType(rtype));
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
