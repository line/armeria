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

import static com.linecorp.armeria.server.streaming.ServerSentEvents.fromEvent;
import static com.linecorp.armeria.server.streaming.ServerSentEvents.fromPublisher;
import static com.linecorp.armeria.server.streaming.ServerSentEvents.fromStream;

import java.util.stream.Stream;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A response converter implementation which creates an {@link HttpResponse} with
 * {@code content-type: text/event-stream}. The objects published from a {@link Publisher} or {@link Stream}
 * would be converted into Server-Sent Events if a {@link ProducesEventStream} annotation is specified
 * on an annotated service method.
 */
public final class ServerSentEventResponseConverterFunction implements ResponseConverterFunction {

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        ResponseHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailers) throws Exception {
        final MediaType contentType = headers.contentType();
        if (contentType != null && contentType.is(MediaType.EVENT_STREAM)) {
            if (result instanceof Publisher) {
                return fromPublisher(headers, (Publisher<?>) result, trailers,
                                     ServerSentEventResponseConverterFunction::toSse);
            }
            if (result instanceof Stream) {
                return fromStream(headers, (Stream<?>) result, trailers, ctx.blockingTaskExecutor(),
                                  ServerSentEventResponseConverterFunction::toSse);
            }
            return fromEvent(headers, toSse(result), trailers);
        }

        if (result instanceof ServerSentEvent) {
            return fromEvent(headers, (ServerSentEvent) result, trailers);
        }

        return ResponseConverterFunction.fallthrough();
    }

    private static ServerSentEvent toSse(@Nullable Object content) {
        if (content == null) {
            return ServerSentEvent.empty();
        }
        if (content instanceof ServerSentEvent) {
            return (ServerSentEvent) content;
        }
        return ServerSentEvent.ofData(String.valueOf(content));
    }
}
