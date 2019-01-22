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
package com.linecorp.armeria.server.annotation;

import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.streaming.ServerSentEvents;

/**
 * A response converter implementation which creates an {@link HttpResponse} with
 * {@code content-type: text/event-stream}. The objects published from a {@link Publisher} or {@link Stream}
 * would be converted into Server-sent Events if a {@link ProducesEventStream} annotation is specified
 * on an annotated service method.
 */
public class ServerSentEventResponseConverterFunction implements ResponseConverterFunction {
    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        HttpHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailingHeaders) throws Exception {
        final MediaType contentType = headers.contentType();
        if (contentType != null && contentType.is(MediaType.EVENT_STREAM)) {
            if (result instanceof Publisher) {
                return ServerSentEvents.fromPublisher(headers, (Publisher<?>) result, trailingHeaders);
            }
            if (result instanceof Stream) {
                return ServerSentEvents.fromStream(headers, (Stream<?>) result, trailingHeaders,
                                                   ctx.blockingTaskExecutor());
            }
            return ServerSentEvents.fromObject(headers, result, trailingHeaders);
        }

        if (result instanceof ServerSentEvent) {
            return ServerSentEvents.fromObject(headers, result, trailingHeaders);
        }

        return ResponseConverterFunction.fallthrough();
    }
}
