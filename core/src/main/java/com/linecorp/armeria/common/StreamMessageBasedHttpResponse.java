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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.stream.NonOverridableStreamMessageWrapper;

import io.netty.util.concurrent.EventExecutor;

final class StreamMessageBasedHttpResponse
        extends NonOverridableStreamMessageWrapper<HttpObject, HttpResponseDuplicator> implements HttpResponse {

    StreamMessageBasedHttpResponse(StreamMessage<? extends HttpObject> delegate) {
        super(delegate);
    }

    @Override
    public HttpResponseDuplicator toDuplicator(EventExecutor executor) {
        return HttpResponse.super.toDuplicator(executor);
    }
}
