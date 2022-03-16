package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLConnection;

import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;

import com.linecorp.armeria.common.util.Exceptions;

class ArmeriaOptionsProviderTest {

    private Class<?> flags;

    @BeforeEach
    private void reloadFlags() throws ClassNotFoundException {
        final FlagsClassLoader classLoader = new FlagsClassLoader();
        flags = classLoader.loadClass(Flags.class.getCanonicalName());
    }

    @Test
    void overrideDefaultArmeriaOptionsProvider() throws Throwable {
        assertFlags("useOpenSsl").isEqualTo(false);
        assertFlags("numCommonBlockingTaskThreads").isEqualTo(100);
    }

    @Test
    void spiInvalidFallbackToDefault() throws Throwable {
        assertFlags("defaultRequestTimeoutMillis")
                .isEqualTo(DefaultFlags.DEFAULT_REQUEST_TIMEOUT_MILLIS);
        assertFlags("defaultBackoffSpec")
                .isEqualTo(DefaultFlags.DEFAULT_BACKOFF_SPEC);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultMaxTotalAttempts", value = "-5")
    void jvmOptionInvalidFallbackToSpi() throws Throwable {
        assertFlags("defaultMaxTotalAttempts").isEqualTo(5);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultMaxClientConnectionAgeMillis", value = "20")
    void jvmOptionPriorityHigherThanSpi() throws Throwable {
        assertFlags("defaultMaxClientConnectionAgeMillis").isEqualTo(20L);
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
