/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.spring;

import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.parseDataSize;

import java.util.function.Consumer;

import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.ArmeriaSettings;

/**
 * A utility class which is used to configure a {@link Server} about {@link ArmeriaSettings}.
 */
public final class ArmeriaConfigurationSettingsUtil {

    /**
     * Configures the {@link ServerBuilder} using the specified {@link ArmeriaSettings}.
     */
    public static void configureSettings(ServerBuilder server, ArmeriaSettings settings) {
        configureIfNonNull(settings.getWorkerGroup(), server::workerGroup);
        configureIfNonNull(settings.getBlockingTaskExecutor(), server::blockingTaskExecutor);

        configureIfNonNull(settings.getMaxNumConnections(), server::maxNumConnections);
        configureIfNonNull(settings.getIdleTimeout(), server::idleTimeout);
        configureIfNonNull(settings.getPingInterval(), server::pingInterval);
        configureIfNonNull(settings.getMaxConnectionAge(), server::maxConnectionAge);
        configureIfNonNull(settings.getMaxNumRequestsPerConnection(), server::maxNumRequestsPerConnection);

        configureIfNonNull(settings.getHttp2InitialConnectionWindowSize(),
                           http2InitialConnectionWindowSize -> server.http2InitialStreamWindowSize(
                                   Ints.saturatedCast(parseDataSize(http2InitialConnectionWindowSize))));
        configureIfNonNull(settings.getHttp2InitialStreamWindowSize(),
                           http2InitialStreamWindowSize -> server.http2InitialStreamWindowSize(
                                   Ints.saturatedCast(parseDataSize(http2InitialStreamWindowSize))));
        configureIfNonNull(settings.getHttp2MaxStreamsPerConnection(), server::http2MaxStreamsPerConnection);
        configureIfNonNull(settings.getHttp2MaxFrameSize(),
                           http2MaxFrameSize -> server.http2MaxFrameSize(
                                   Ints.saturatedCast(parseDataSize(http2MaxFrameSize))));
        configureIfNonNull(settings.getHttp2MaxHeaderListSize(),
                           http2MaxHeaderListSize -> server.http2MaxHeaderListSize(
                                   parseDataSize(http2MaxHeaderListSize)));

        configureIfNonNull(settings.getHttp1MaxInitialLineLength(),
                           http1MaxInitialLineLength -> server.http1MaxInitialLineLength(
                                   Ints.saturatedCast(parseDataSize(http1MaxInitialLineLength))));
        configureIfNonNull(settings.getHttp1MaxHeaderSize(),
                           http1MaxHeaderSize -> server.http1MaxHeaderSize(
                                   Ints.saturatedCast(parseDataSize(http1MaxHeaderSize))));
        configureIfNonNull(settings.getHttp1MaxChunkSize(),
                           http1MaxChunkSize -> server.http1MaxChunkSize(
                                   Ints.saturatedCast(parseDataSize(http1MaxChunkSize))));

        configureIfNonNull(settings.getAccessLogFormat(), server::accessLogFormat);
        configureIfNonNull(settings.getAccessLogger(), server::accessLogger);

        configureIfNonNull(settings.getRequestTimeout(), server::requestTimeout);
        configureIfNonNull(settings.getMaxRequestLength(),
                           maxRequestLength -> server.maxRequestLength(parseDataSize(maxRequestLength)));
        configureIfNonNull(settings.getVerboseResponses(), server::verboseResponses);
    }

    private ArmeriaConfigurationSettingsUtil() {}

    private static <T> void configureIfNonNull(@Nullable T nullable, Consumer<T> block) {
        if (nullable != null) {
            block.accept(nullable);
        }
    }
}
