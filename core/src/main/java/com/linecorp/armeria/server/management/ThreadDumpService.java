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

package com.linecorp.armeria.server.management;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

enum ThreadDumpService implements HttpService {

    INSTANCE;

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final boolean acceptJson = req.headers().accept().stream()
                                      .anyMatch(MediaType.JSON::is);

        final ThreadInfo[] threadInfos =
                ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);

        if (acceptJson) {
            return HttpResponse.ofJson(threadInfos);
        } else {
            final String threadDump = Arrays.stream(threadInfos)
                                            .map(ThreadInfo::toString)
                                            .collect(Collectors.joining());
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, threadDump);
        }
    }
}
