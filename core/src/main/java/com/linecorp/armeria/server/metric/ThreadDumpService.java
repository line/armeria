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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

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
 * An {@link HttpService} that dumps the thread information for all live threads with stack trace.
 * If {@link MediaType#JSON} is specified in {@link HttpHeaderNames#ACCEPT}, the thread information will be
 * converted to a JSON. Otherwise, the thread dump will be converted to a plain text.
 */
@UnstableApi
public final class ThreadDumpService extends AbstractHttpService {

    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final ThreadDumpService INSTANCE = new ThreadDumpService();
    private static final Splitter ACCEPT_SPLITTER = Splitter.on(',').trimResults();

    /**
     * Returns a singleton {@link ThreadDumpService}.
     */
    public static ThreadDumpService of() {
        return INSTANCE;
    }

    ThreadDumpService() {}

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        boolean acceptJson = false;
        final String accept = req.headers().get(HttpHeaderNames.ACCEPT);
        if (accept != null) {
            acceptJson = Streams.stream(ACCEPT_SPLITTER.split(accept))
                              .anyMatch(accept0 -> MediaType.JSON.is(MediaType.parse(accept0)));
        }

        final ThreadInfo[] threadInfos =
                ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);

        if (acceptJson) {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsBytes(threadInfos));
        } else {
            final String threadDump = Arrays.stream(threadInfos)
                                            .map(Objects::toString)
                                            .collect(Collectors.joining());
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, threadDump);
        }
    }
}
