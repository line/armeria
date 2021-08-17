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
package com.linecorp.armeria.common.util;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JavaVersionSpecific;

/**
 * Provides utilities for accessing the information about the current system and process.
 */
public final class SystemInfo {

    private static final Logger logger = LoggerFactory.getLogger(SystemInfo.class);

    private static final int JAVA_VERSION;

    private static final boolean JETTY_ALPN_OPTIONAL_OR_AVAILABLE;

    private static final OsType osType;

    static {
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
            boolean temp;
            try {
                // Always use bootstrap class loader.
                Class.forName("sun.security.ssl.ALPNExtension", true, null);
                temp = true;
            } catch (Throwable ignore) {
                // alpn-boot was not loaded.
                temp = false;
            }
            JETTY_ALPN_OPTIONAL_OR_AVAILABLE = temp;
        }

        final String osName = Ascii.toUpperCase(System.getProperty("os.name", ""));
        if (osName.startsWith("WINDOWS")) {
            osType = OsType.WINDOWS;
        } else if (osName.startsWith("LINUX")) {
            osType = OsType.LINUX;
        } else if (osName.startsWith("MAC")) {
            osType = OsType.MAC;
        } else {
            osType = OsType.OTHERS;
        }
    }

    /**
     * Returns the major version of the current Java Virtual Machine.
     */
    public static int javaVersion() {
        return JAVA_VERSION;
    }

    /**
     * Returns the local hostname.
     */
    public static String hostname() {
        return Hostname.HOSTNAME;
    }

    /**
     * Whether the environment either supports ALPN natively or includes Jetty ALPN.
     */
    public static boolean jettyAlpnOptionalOrAvailable() {
        return JETTY_ALPN_OPTIONAL_OR_AVAILABLE;
    }

    /**
     * Returns the current process ID.
     *
     * @throws IllegalStateException if failed to retrieve the current process ID.
     */
    public static int pid() {
        if (Pid.PID <= 0) {
            throw new IllegalStateException("Failed to retrieve the current PID.");
        }
        return Pid.PID;
    }

    /**
     * Returns the non-loopback {@link Inet4Address} whose {@link NetworkInterface#getIndex()} is the lowest.
     *
     * @see Flags#preferredIpV4Addresses()
     */
    @Nullable
    public static Inet4Address defaultNonLoopbackIpV4Address() {
        return DefaultNonLoopbackIPv4Address.defaultNonLoopbackIpV4Address;
    }

    /**
     * Returns the number of microseconds since the epoch (00:00:00, 01-Jan-1970, GMT). The precision of the
     * returned value may vary depending on {@linkplain #javaVersion() Java version}. Currently, Java 9 or
     * above is required for microsecond precision. {@code System.currentTimeMillis() * 1000} is returned on
     * Java 8.
     */
    public static long currentTimeMicros() {
        return JavaVersionSpecific.get().currentTimeMicros();
    }

    /**
     * Returns the operating system for the currently running process.
     */
    public static OsType osType() {
        return osType;
    }

    /**
     * Returns {@code true} if the operating system is Linux.
     */
    public static boolean isLinux() {
        return osType == OsType.LINUX;
    }

    private SystemInfo() {}

    private static final class Hostname {

        private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
                "^(?:[-_a-zA-Z0-9]|[-_a-zA-Z0-9][-_.a-zA-Z0-9]*[-_a-zA-Z0-9])$");

        static final String HOSTNAME;

        static {
            // Try /proc/sys/kernel/hostname on Linux.
            String hostname = null;
            if (isLinux()) {
                try {
                    final List<String> lines = Files.readAllLines(Paths.get("/proc/sys/kernel/hostname"));
                    if (!lines.isEmpty()) {
                        hostname = normalizeHostname(lines.get(0));
                    }
                    if (hostname != null) {
                        logger.info("hostname: {} (from /proc/sys/kernel/hostname)", hostname);
                    } else {
                        logger.debug("/proc/sys/kernel/hostname does not contain a valid hostname: {}", lines);
                    }
                } catch (Throwable t) {
                    logger.debug("Failed to get the hostname from /proc/sys/kernel/hostname; " +
                                 "using the 'hostname' command instead", t);
                }
            }

            // Try /usr/bin/hostname.
            if (hostname == null) {
                Process process = null;
                try {
                    process = Runtime.getRuntime().exec("hostname");
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(process.getInputStream()));
                    final String line = in.readLine();
                    if (line != null) {
                        hostname = normalizeHostname(line);
                    }

                    if (hostname != null) {
                        logger.info("hostname: {} (from 'hostname' command)", hostname);
                    } else {
                        logger.debug("The 'hostname' command returned a non-hostname ({}); " +
                                     "using InetAddress.getLocalHost() instead", line);
                    }
                } catch (Throwable t) {
                    logger.debug("Failed to get the hostname using the 'hostname' command; " +
                                 "using InetAddress.getLocalHost() instead", t);
                } finally {
                    if (process != null) {
                        process.destroy();
                    }
                }
            }

            if (hostname == null) {
                try {
                    final String jdkHostname = InetAddress.getLocalHost().getHostName();
                    hostname = normalizeHostname(jdkHostname);
                    if (hostname == null) {
                        logger.warn("InetAddress.getLocalHost() returned an invalid hostname ({}); " +
                                    "using 'localhost' instead", jdkHostname);
                    } else {
                        logger.info("hostname: {} (from InetAddress.getLocalHost())", hostname);
                    }
                } catch (Throwable t) {
                    logger.warn("Failed to get the hostname using InetAddress.getLocalHost(); " +
                                "using 'localhost' instead", t);
                }
            }

            HOSTNAME = firstNonNull(hostname, "localhost");
        }

        @Nullable
        private static String normalizeHostname(String line) {
            final String hostname = IDN.toASCII(line.trim(), IDN.ALLOW_UNASSIGNED);
            if (!HOSTNAME_PATTERN.matcher(hostname).matches()) {
                return null;
            }
            return Ascii.toLowerCase(hostname);
        }
    }

    private static final class Pid {

        static final int PID;

        static {
            int pid = -1;
            // Try ProcessHandle.pid() if Java 9+.
            if (javaVersion() >= 9) {
                try {
                    final Class<?> handleClass = Class.forName("java.lang.ProcessHandle", true,
                                                               Process.class.getClassLoader());
                    final Method currentMethod = handleClass.getDeclaredMethod("current");
                    final Method pidMethod = handleClass.getDeclaredMethod("pid");
                    final Object currentHandle = currentMethod.invoke(null);
                    final Object result = pidMethod.invoke(currentHandle);
                    pid = validatePid(result);
                    if (pid <= 0) {
                        logger.warn("ProcessHandle.pid() returned an invalid PID: {}", result);
                    } else {
                        logger.info("PID: {} (from ProcessHandle.pid())", pid);
                    }
                } catch (Throwable t) {
                    logFailure("ProcessHandle.current()", true, t);
                }
            }

            // Try sun.management.VMManagement.getProcessId().
            if (pid <= 0) {
                try {
                    final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
                    final Field jvmField = runtime.getClass().getDeclaredField("jvm");
                    jvmField.setAccessible(true);
                    final Object jvm = jvmField.get(runtime);
                    final Method getProcessIdMethod = jvm.getClass().getDeclaredMethod("getProcessId");
                    getProcessIdMethod.setAccessible(true);
                    final Object result = getProcessIdMethod.invoke(jvm);
                    pid = validatePid(result);
                    if (pid <= 0) {
                        logger.warn("VMManagement.getProcessId() returned an invalid PID: {}", result);
                    } else {
                        logger.info("PID: {} (from VMManagement.getProcessId())", pid);
                    }
                } catch (Throwable t) {
                    logFailure("VMManagement.getProcessId()", false, t);
                }
            }

            // Try /proc/self (Linux only)
            if (pid <= 0 && isLinux()) {
                try {
                    final Path path = Paths.get("/proc/self");
                    if (Files.isSymbolicLink(path)) {
                        final Path realPath = path.toRealPath();
                        pid = validatePid(Integer.parseInt(realPath.getFileName().toString()));
                        if (pid <= 0) {
                            logger.warn("/proc/self does not refer to a PID-named file: {}", realPath);
                        } else {
                            logger.info("PID: {} (from /proc/self)", pid);
                        }
                    }
                } catch (Throwable t) {
                    logFailure("/proc/self", false, t);
                }
            }

            // Try RuntimeMXBean.getName() as the last resort.
            if (pid <= 0) {
                try {
                    final String result = ManagementFactory.getRuntimeMXBean().getName();
                    final String[] values = result.split("@");
                    pid = validatePid(values.length > 0 ? Integer.parseInt(values[0]) : -1);
                    if (pid <= 0) {
                        logger.warn("RuntimeMXBean.getName() returned an unexpected value: {}", result);
                    } else {
                        logger.info("PID: {} (from RuntimeMXBean.getName())", pid);
                    }
                } catch (Throwable t) {
                    logFailure("RuntimeMXBean.getName()", true, t);
                }
            }

            PID = pid;
        }

        private static int validatePid(@Nullable Object value) {
            if (!(value instanceof Number)) {
                return -1;
            } else {
                 final int pid = ((Number) value).intValue();
                 return pid > 0 ? pid : -1;
            }
        }

        private static void logFailure(String method, boolean warn, Throwable cause) {
            cause = Exceptions.peel(cause);
            if (cause instanceof UnsupportedOperationException ||
                cause instanceof SecurityException ||
                cause instanceof IllegalAccessException) {
                logger.debug("An access to {} not possible due to platform restriction:", method, cause);
                return;
            }

            final String msg = "Failed to retrieve the current PID from {}:";
            if (warn) {
                logger.warn(msg, method, cause);
            } else {
                logger.debug(msg, method, cause);
            }
        }
    }

    private static final class DefaultNonLoopbackIPv4Address {

        // Forked from InetUtils in spring-cloud-common 3.0.0.M1 at e7bb7ed3ae19a91c6fa7b3b698dd9788f70df7d4
        // - Use CIDR in isPreferredAddress instead of regular expression.

        @Nullable
        static final Inet4Address defaultNonLoopbackIpV4Address;

        static {
            Inet4Address result = null;
            String nicDisplayName = null;
            try {
                int lowest = Integer.MAX_VALUE;
                for (final Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
                     nics.hasMoreElements();) {
                    final NetworkInterface nic = nics.nextElement();
                    if (!nic.isUp()) {
                        logger.debug("{} is down. Trying next.", nic.getDisplayName());
                        continue;
                    }

                    // The NIC whose index is the lowest will be likely the valid IPv4 address.
                    // See https://github.com/spring-cloud/spring-cloud-commons/issues/82.
                    if (nic.getIndex() < lowest || result == null) {
                        lowest = nic.getIndex();
                    } else {
                        logger.debug("{} has higher index({}) than {}. Skip.",
                                     nic.getDisplayName(), nic.getIndex(), result);
                        continue;
                    }

                    for (final Enumeration<InetAddress> addrs = nic.getInetAddresses();
                         addrs.hasMoreElements();) {
                        final InetAddress address = addrs.nextElement();
                        if (!(address instanceof Inet4Address)) {
                            logger.debug("{} of {} is not an Inet4Address. Trying next.",
                                         address, nic.getDisplayName());
                            continue;
                        }
                        if (address.isLoopbackAddress()) {
                            logger.debug("{} of {} is a loopback address. Trying next.",
                                         address, nic.getDisplayName());
                            continue;
                        }
                        if (!isPreferredAddress(address)) {
                            logger.debug("{} of {} is not a preferred IP address. Trying next.",
                                         address, nic.getDisplayName());
                            continue;
                        }
                        result = (Inet4Address) address;
                        nicDisplayName = nic.getDisplayName();
                    }
                }
            } catch (IOException ex) {
                logger.warn("Could not get a non-loopback IPv4 address:", ex);
            }

            if (result != null) {
                defaultNonLoopbackIpV4Address = result;
                logger.info("defaultNonLoopbackIpV4Address: {} (from: {})",
                            defaultNonLoopbackIpV4Address, nicDisplayName);
            } else {
                Inet4Address temp = null;
                try {
                    final InetAddress localHost = InetAddress.getLocalHost();
                    if (localHost instanceof Inet4Address) {
                        temp = (Inet4Address) localHost;
                        logger.info("defaultNonLoopbackIpV4Address: {} (from: InetAddress.getLocalHost())",
                                    temp);
                    } else {
                        logger.warn("Could not get a non-loopback IPv4 address. " +
                                    "defaultNonLoopbackIpV4Address is set to null.");
                    }
                } catch (UnknownHostException e) {
                    logger.warn("Unable to retrieve the localhost address. " +
                                "defaultNonLoopbackIpV4Address is set to null.", e);
                }
                defaultNonLoopbackIpV4Address = temp;
            }
        }

        private static boolean isPreferredAddress(InetAddress address) {
            final Predicate<InetAddress> predicates = Flags.preferredIpV4Addresses();
            if (predicates == null) {
                return true;
            }
            return predicates.test(address);
        }
    }
}
