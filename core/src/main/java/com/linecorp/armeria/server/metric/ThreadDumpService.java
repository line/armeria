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
 * under the License
 */

package com.linecorp.armeria.server.metric;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.lang.Thread.State;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Splitter;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An {@link HttpService} that dumps the thread info for all live threads with stack trace.
 * If {@link MediaType#JSON} is specified in {@link HttpHeaderNames#ACCEPT_ENCODING}, the thread info will be
 * converted as a JSON array.
 */
@UnstableApi
public final class ThreadDumpService extends AbstractHttpService {

    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final ThreadDumpService INSTANCE = new ThreadDumpService();
    private static final Splitter ACCEPT_ENCODING_SPLITTER = Splitter.on(',').trimResults();

    /**
     * Returns a singleton {@link ThreadDumpService}.
     */
    public static ThreadDumpService of() {
        return INSTANCE;
    }

    ThreadDumpService() {}

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {

        boolean hasJson = false;
        final List<String> acceptEncodings = req.headers().getAll(HttpHeaderNames.ACCEPT_ENCODING);
        if (acceptEncodings != null) {
            hasJson = acceptEncodings.stream().anyMatch(acceptEncoding -> {
                return Streams.stream(ACCEPT_ENCODING_SPLITTER.split(acceptEncoding))
                              .anyMatch(accept -> MediaType.JSON.is(MediaType.parse(accept)));
            });
        }

        if (hasJson) {
            final List<ThreadInfo> threadInfos = Thread.getAllStackTraces().entrySet().stream().map(entry -> {
                final Thread thread = entry.getKey();
                final List<String> stack = Arrays.stream(entry.getValue())
                                                 .map(StackTraceElement::toString)
                                                 .collect(toImmutableList());

                // java.lang.management.ThreadInfo.isDaemon() and getPriority() are added at Java 9
                return new ThreadInfo(thread.getId(), thread.getName(), thread.isDaemon(),
                                      thread.getState(), thread.getPriority(), stack);
            }).collect(toImmutableList());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsBytes(threadInfos));
        } else {
            final java.lang.management.ThreadInfo[] threadInfos =
                    ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
            final String threadDump = Arrays.stream(threadInfos)
                                            .map(Objects::toString)
                                            .collect(Collectors.joining());
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, threadDump);
        }
    }

    static final class ThreadInfo {
        private final long id;
        private final String name;
        private final boolean daemon;
        private final State state;
        private final int priority;
        private final List<String> stack;

        ThreadInfo(long id, String name, boolean daemon, State state, int priority,
                   List<String> stack) {
            this.id = id;
            this.name = name;
            this.daemon = daemon;
            this.state = state;
            this.priority = priority;
            this.stack = stack;
        }

        @JsonProperty
        long id() {
            return id;
        }

        @JsonProperty
        String name() {
            return name;
        }

        @JsonProperty
        boolean daemon() {
            return daemon;
        }

        @JsonProperty
        State state() {
            return state;
        }

        @JsonProperty
        int priority() {
            return priority;
        }

        @JsonProperty
        List<String> stack() {
            return stack;
        }
    }
}
