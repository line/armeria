/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.common;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.server.TransientServiceOption;

import io.netty.handler.ssl.OpenSsl;

/**
 * Default value of {@link Flags} and @{@link ArmeriaOptionsProvider}.
 */
enum DefaultFlags {
    INSTANCE;

    static final String VERBOSE_EXCEPTION_SAMPLER_SPEC = "rate-limit=10";
    static final Sampler<Class<? extends Throwable>> VERBOSE_EXCEPTION_SAMPLER =
            new ExceptionSampler(VERBOSE_EXCEPTION_SAMPLER_SPEC);

    static final boolean VERBOSE_SOCKET_EXCEPTIONS = false;

    static final boolean VERBOSE_RESPONSES = false;

    @Nullable
    static final String REQUEST_CONTEXT_STORAGE_PROVIDER = null;

    static final boolean WARN_NETTY_VERSIONS = true;

    static final boolean USE_EPOLL = TransportType.EPOLL.isAvailable();
    static final TransportType TRANSPORT_TYPE = USE_EPOLL ? TransportType.EPOLL : TransportType.NIO;

    static final Boolean useOpenSsl = OpenSsl.isAvailable();
    static final Boolean dumpOpenSslInfo = false;

    static final int MAX_NUM_CONNECTIONS = Integer.MAX_VALUE;

    private static final int NUM_CPU_CORES = Runtime.getRuntime().availableProcessors();
    static final int NUM_COMMON_WORKERS = NUM_CPU_CORES * 2;

    static final int NUM_COMMON_BLOCKING_TASK_THREADS = 200; // from Tomcat default maxThreads

    static final long DEFAULT_MAX_REQUEST_LENGTH = 10 * 1024 * 1024; // 10 MiB

    static final long DEFAULT_MAX_RESPONSE_LENGTH = 10 * 1024 * 1024; // 10 MiB

    static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 10 * 1000; // 10 seconds

    // Use slightly greater value than the default request timeout so that clients have a higher chance of
    // getting proper 503 Service Unavailable response when server-side timeout occurs.
    static final long DEFAULT_RESPONSE_TIMEOUT_MILLIS = 15 * 1000; // 15 seconds

    static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 3200; // 3.2 seconds

    static final long DEFAULT_WRITE_TIMEOUT_MILLIS = 1000; // 1 second

    // Use slightly greater value than the client-side default so that clients close the connection more often.
    static final long DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS = 15000; // 15 seconds

    static final long DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS = 10000; // 10 seconds

    static final int DEFAULT_MAX_HTTP1_INITIAL_LINE_LENGTH = 4096; // from Netty

    static final int DEFAULT_MAX_HTTP1_HEADER_SIZE = 8192; // from Netty

    static final int DEFAULT_HTTP1_MAX_CHUNK_SIZE = 8192; // from Netty

    static final boolean DEFAULT_USE_HTTP2_PREFACE = true;
    static final boolean DEFAULT_USE_HTTP1_PIPELINING = false;

    static final long DEFAULT_PING_INTERVAL_MILLIS = 0; // Disabled

    static final int DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION = 0; // Disabled

    static final long DEFAULT_MAX_CONNECTION_AGE_MILLIS = 0; // Disabled

    static final long DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS = 1000000;

    static final int DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE = 1024 * 1024; // 1MiB

    static final int DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE = 1024 * 1024; // 1MiB

    static final int DEFAULT_HTTP2_MAX_FRAME_SIZE = 16384; // From HTTP/2 specification

    // Can't use 0xFFFFFFFFL because some implementations use a signed 32-bit integer to store HTTP/2 SETTINGS
    // parameter values, thus anything greater than 0x7FFFFFFF will break them or make them unhappy.
    static final long DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION = Integer.MAX_VALUE;

    // from Netty default maxHeaderSize
    static final long DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE = 8192;

    static final String DEFAULT_BACKOFF_SPEC = "exponential=200:10000,jitter=0.2";

    static final int DEFAULT_MAX_TOTAL_ATTEMPTS = 10;

    static final String ROUTE_CACHE_SPEC = "maximumSize=4096";

    static final String ROUTE_DECORATOR_CACHE_SPEC = "maximumSize=4096";

    static final String PARSED_PATH_CACHE_SPEC = "maximumSize=4096";

    static final String HEADER_VALUE_CACHE_SPEC = "maximumSize=4096";

    private static final Splitter CSV_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();
    static final List<String> CACHED_HEADERS = CSV_SPLITTER
            .splitToList(":authority,:scheme,:method,accept-encoding,content-type");

    static final String FILE_SERVICE_CACHE_SPEC = "maximumSize=1024";

    static final String DNS_CACHE_SPEC = "maximumSize=4096";

    @Nullable
    static final Predicate<InetAddress> PREFERRED_IP_V4_ADDRESSES = null;

    static final boolean USE_JDK_DNS_RESOLVER = false;

    static final boolean REPORT_BLOCKED_EVENT_LOOP = true;

    static final boolean VALIDATE_HEADERS = true;

    static final boolean DEFAULT_TLS_ALLOW_UNSAFE_CIPHERS = false;

    static final Set<TransientServiceOption> TRANSIENT_SERVICE_OPTIONS = ImmutableSet.of();

    static final boolean USE_DEFAULT_SOCKET_OPTIONS = true;

    static final boolean DEFAULT_USE_LEGACY_ROUTE_DECORATOR_ORDERING = false;

    static final boolean ALLOW_DOUBLE_DOTS_IN_QUERY_STRING = false;
}
