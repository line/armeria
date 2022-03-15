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
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;

import com.linecorp.armeria.common.util.Exceptions;

class ArmeriaOptionsProviderTest {

    @Test
    void overrideDefaultArmeriaOptionsProvider() {
        assertThat(Flags.useOpenSsl()).isEqualTo(false);
        assertThat(Flags.numCommonBlockingTaskThreads()).isEqualTo(100);
    }

    @Test
    void spiInvalidFallbackToDefault() {
        assertThat(Flags.defaultRequestTimeoutMillis())
                .isEqualTo(DefaultFlags.DEFAULT_REQUEST_TIMEOUT_MILLIS);
        assertThat(Flags.defaultBackoffSpec())
                .isEqualTo(DefaultFlags.DEFAULT_BACKOFF_SPEC);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultMaxTotalAttempts", value = "-5")
    void jvmOptionInvalidFallbackToSpi() {
        assertThat(Flags.defaultMaxTotalAttempts()).isEqualTo(5);
    }

    @Test
    @SetSystemProperty(key = "com.linecorp.armeria.defaultMaxClientConnectionAgeMillis", value = "20")
    void jvmOptionPriorityHigherThanSpi() {
        assertFlags("defaultMaxClientConnectionAgeMillis", long.class).isEqualTo(20L);
    }

    //TODO refactor test
    private static ObjectAssert<Object> assertFlags(String flagsMethod, Class<?> returnClass) {
        try {
            final FlagsClassLoader classLoader = new FlagsClassLoader();
            final Class<?> flags = classLoader.loadClass("com.linecorp.armeria.common.Flags");
            classLoader.loadClass("com.linecorp.armeria.common.CustomArmeriaOptionsProvider");
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
