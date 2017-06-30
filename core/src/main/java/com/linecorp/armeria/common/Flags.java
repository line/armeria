/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common;

import java.util.function.IntPredicate;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import javax.net.ssl.SSLEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;

import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryingClient;

import io.netty.channel.epoll.Epoll;
import io.netty.handler.ssl.OpenSsl;

/**
 * The system properties that affect Armeria's runtime behavior.
 */
public final class Flags {

    private static final Logger logger = LoggerFactory.getLogger(Flags.class);

    private static final String PREFIX = "com.linecorp.armeria.";

    private static final int NUM_CPU_CORES = Runtime.getRuntime().availableProcessors();

    private static final boolean VERBOSE_EXCEPTION = getBoolean("verboseExceptions", false);

    private static final boolean USE_EPOLL = getBoolean("useEpoll", Epoll.isAvailable(),
                                                        value -> Epoll.isAvailable() || !value);
    private static final boolean USE_OPENSSL = getBoolean("useOpenSsl", OpenSsl.isAvailable(),
                                                          value -> OpenSsl.isAvailable() || !value);

    private static final int DEFAULT_NUM_COMMON_BOSSES = 1;
    private static final int NUM_COMMON_BOSSES =
            getInt("numCommonBosses", DEFAULT_NUM_COMMON_BOSSES, value -> value > 0);

    private static final int DEFAULT_NUM_COMMON_WORKERS = NUM_CPU_CORES * 2;
    private static final int NUM_COMMON_WORKERS =
            getInt("numCommonWorkers", DEFAULT_NUM_COMMON_WORKERS, value -> value > 0);

    private static final long DEFAULT_DEFAULT_CONNECT_TIMEOUT_MILLIS = 3200; // 3.2 seconds
    private static final long DEFAULT_CONNECT_TIMEOUT_MILLIS =
            getLong("defaultConnectTimeoutMillis",
                    DEFAULT_DEFAULT_CONNECT_TIMEOUT_MILLIS,
                    value -> value > 0);

    // Use slightly greater value than the client-side default so that clients close the connection more often.
    private static final long DEFAULT_DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS = 15000; // 15 seconds
    private static final long DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS =
            getLong("defaultServerIdleTimeoutMillis",
                    DEFAULT_DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS,
                    value -> value >= 0);

    private static final long DEFAULT_DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS = 10000; // 10 seconds
    private static final long DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS =
            getLong("defaultClientIdleTimeoutMillis",
                    DEFAULT_DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS,
                    value -> value >= 0);

    private static final boolean DEFAULT_USE_HTTP2_PREFACE = getBoolean("defaultUseHttp2Preface", false);
    private static final boolean DEFAULT_USE_HTTP1_PIPELINING = getBoolean("defaultUseHttp1Pipelining", true);

    private static final int DEFAULT_DEFAULT_BACKOFF_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_BACKOFF_MAX_ATTEMPTS =
            getInt("defaultBackoffMaxAttempts", DEFAULT_DEFAULT_BACKOFF_MAX_ATTEMPTS, value -> value > 0);

    private static final int DEFAULT_DEFAULT_EXPONENTIAL_BACKOFF_INITIAL_DELAY_MILLIS = 1000;
    private static final int DEFAULT_EXPONENTIAL_BACKOFF_INITIAL_DELAY_MILLIS =
            getInt("defaultExponentialBackoffInitialDelayMillis",
                   DEFAULT_DEFAULT_EXPONENTIAL_BACKOFF_INITIAL_DELAY_MILLIS, value -> value >= 0);

    static {
        if (!Epoll.isAvailable()) {
            final Throwable cause = filterCause(Epoll.unavailabilityCause());
            logger.info("/dev/epoll not available: {}", cause.toString());
        } else if (USE_EPOLL) {
            logger.info("Using /dev/epoll");
        }

        if (!OpenSsl.isAvailable()) {
            final Throwable cause = filterCause(OpenSsl.unavailabilityCause());
            logger.info("OpenSSL not available: {}", cause.toString());
        } else if (USE_OPENSSL) {
            logger.info("Using OpenSSL: {}, 0x{}",
                        OpenSsl.versionString(),
                        Long.toHexString(OpenSsl.version() & 0xFFFFFFFFL));
        }
    }

    /**
     * Returns whether the verbose exception mode is enabled. When enabled, the exceptions frequently thrown by
     * Armeria will have full stack trace. When disabled, such exceptions will have empty stack trace to
     * eliminate the cost of capturing the stack trace.
     *
     * <p>This flag is disabled by default. Specify the {@code -Dcom.linecorp.armeria.verboseExceptions=true}
     * JVM option to enable it.
     */
    public static boolean verboseExceptions() {
        return VERBOSE_EXCEPTION;
    }

    /**
     * Returns whether the JNI-based {@code /dev/epoll} socket I/O is enabled. When enabled on Linux, Armeria
     * uses {@code /dev/epoll} directly for socket I/O. When disabled, {@code java.nio} socket API is used
     * instead.
     *
     * <p>This flag is enabled by default for supported platforms. Specify the
     * {@code -Dcom.linecorp.armeria.useEpoll=false} JVM option to disable it.
     */
    public static boolean useEpoll() {
        return USE_EPOLL;
    }

    /**
     * Returns whether the JNI-based TLS support with OpenSSL is enabled. When enabled, Armeria uses OpenSSL
     * for processing TLS connections. When disabled, the current JVM's default {@link SSLEngine} is used
     * instead.
     *
     * <p>This flag is enabled by default for supported platforms. Specify the
     * {@code -Dcom.linecorp.armeria.useOpenSsl=false} JVM option to disable it.
     */
    public static boolean useOpenSsl() {
        return USE_OPENSSL;
    }

    /**
     * Returns the default number of {@linkplain CommonPools#bossGroup() common boss group} threads.
     *
     * <p>The default value of this flag is {@value #DEFAULT_NUM_COMMON_BOSSES}. Specify the
     * {@code -Dcom.linecorp.armeria.numCommonBosses=<integer>} to override the default value.
     */
    public static int numCommonBosses() {
        return NUM_COMMON_BOSSES;
    }

    /**
     * Returns the default number of {@linkplain CommonPools#workerGroup() common worker group} threads.
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>The default value of this flag is {@code 2 * <numCpuCores>}. Specify the
     * {@code -Dcom.linecorp.armeria.numCommonWorkers=<integer>} to override the default value.
     */
    public static int numCommonWorkers() {
        return NUM_COMMON_WORKERS;
    }

    /**
     * Returns the default timeout of a socket connection attempt in milliseconds.
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_CONNECT_TIMEOUT_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultConnectTimeoutMillis=<integer>} to override the default value.
     */
    public static long defaultConnectTimeoutMillis() {
        return DEFAULT_CONNECT_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default server-side idle timeout of a connection for keep-alive in milliseconds.
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultServerIdleTimeoutMillis=<integer>} to override the default value.
     */
    public static long defaultServerIdleTimeoutMillis() {
        return DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default client-side idle timeout of a connection for keep-alive in milliseconds.
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>This default value of this flag is {@value #DEFAULT_DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultClientIdleTimeoutMillis=<integer>} to override the default value.
     */
    public static long defaultClientIdleTimeoutMillis() {
        return DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default value of the {@link ClientFactoryBuilder#useHttp2Preface(boolean)} option.
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUseHttp2Preface=true} to enable it.
     */
    public static boolean defaultUseHttp2Preface() {
        return DEFAULT_USE_HTTP2_PREFACE;
    }

    /**
     * Returns the default value of the {@link ClientFactoryBuilder#useHttp1Pipelining(boolean)} option.
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>This flag is enabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUseHttp1Pipelining=false} to disable it.
     */
    public static boolean defaultUseHttp1Pipelining() {
        return DEFAULT_USE_HTTP1_PIPELINING;
    }

    /**
     * Returns the default value of the {@code defaultBackoffMaxAttempts} parameter when instantiating a
     * {@link RetryingClient} and its subtypes. Note that this value has effect only if a user did not specify
     * the {@code defaultBackoffMaxAttempts} in the constructor call.
     *
     * <p>The default value of this flag is {@value DEFAULT_DEFAULT_BACKOFF_MAX_ATTEMPTS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultBackoffMaxAttempts=<integer>} to override the default value.
     */
    public static int defaultBackoffMaxAttempts() {
        return DEFAULT_BACKOFF_MAX_ATTEMPTS;
    }

    /**
     * Returns the default value of the {@code initialDelayMillis} parameter when instantiating
     * a {@link Backoff} using {@link Backoff#exponential(long, long)}. Note that this value
     * has effect only if a user did not specify the {@code defaultExponentialBackoffInitialDelayMillis}
     * in the constructor call.
     *
     * <p>The default value of this flag is {@value DEFAULT_DEFAULT_EXPONENTIAL_BACKOFF_INITIAL_DELAY_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultExponentialBackoffInitialDelayMillis=<integer>}
     * to override the default value.
     */
    public static int defaultExponentialBackoffInitialDelayMillis() {
        return DEFAULT_EXPONENTIAL_BACKOFF_INITIAL_DELAY_MILLIS;
    }

    private static boolean getBoolean(String name, boolean defaultValue) {
        return getBoolean(name, defaultValue, value -> true);
    }

    private static boolean getBoolean(String name, boolean defaultValue, Predicate<Boolean> validator) {
        return "true".equals(getNormalized(name, String.valueOf(defaultValue), value -> {
            if ("true".equals(value)) {
                return validator.test(true);
            }

            if ("false".equals(value)) {
                return validator.test(false);
            }

            return false;
        }));
    }

    private static int getInt(String name, int defaultValue, IntPredicate validator) {
        return Integer.parseInt(getNormalized(name, String.valueOf(defaultValue), value -> {
            try {
                return validator.test(Integer.parseInt(value));
            } catch (Exception e) {
                // null or non-integer
                return false;
            }
        }));
    }

    private static long getLong(String name, long defaultValue, LongPredicate validator) {
        return Long.parseLong(getNormalized(name, String.valueOf(defaultValue), value -> {
            try {
                return validator.test(Long.parseLong(value));
            } catch (Exception e) {
                // null or non-integer
                return false;
            }
        }));
    }

    private static String getNormalized(String name, String defaultValue, Predicate<String> validator) {
        final String fullName = PREFIX + name;
        final String value = getLowerCased(fullName);
        if (value == null) {
            logger.info("{}: {} (default)", fullName, defaultValue);
            return defaultValue;
        }

        if (validator.test(value)) {
            logger.info("{}: {}", fullName, value);
            return value;
        }

        logger.info("{}: {} (default instead of: {})", fullName, defaultValue, value);
        return defaultValue;
    }

    private static String getLowerCased(String fullName) {
        String value = System.getProperty(fullName);
        if (value != null) {
            value = Ascii.toLowerCase(value);
        }
        return value;
    }

    private static Throwable filterCause(Throwable cause) {
        if (cause instanceof ExceptionInInitializerError) {
            return cause.getCause();
        }

        return cause;
    }

    private Flags() {}
}
