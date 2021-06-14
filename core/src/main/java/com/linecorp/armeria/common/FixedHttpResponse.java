/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.common.stream.OneElementFixedStreamMessage;
import com.linecorp.armeria.common.stream.RegularFixedStreamMessage;
import com.linecorp.armeria.common.stream.ThreeElementFixedStreamMessage;
import com.linecorp.armeria.common.stream.TwoElementFixedStreamMessage;

/**
 * An {@link HttpResponse} optimized for when all the {@link HttpObject}s that will be published are known at
 * construction time.
 */
final class FixedHttpResponse {

    // TODO(minwoox): Override toDuplicator(...) methods for optimization

    static final class OneElementFixedHttpResponse
            extends OneElementFixedStreamMessage<HttpObject> implements HttpResponse {
        OneElementFixedHttpResponse(ResponseHeaders headers) {
            super(headers);
        }
    }

    static final class TwoElementFixedHttpResponse
            extends TwoElementFixedStreamMessage<HttpObject> implements HttpResponse {
        TwoElementFixedHttpResponse(ResponseHeaders headers, HttpObject obj) {
            super(headers, obj);
        }
    }

    static final class ThreeElementFixedHttpResponse
            extends ThreeElementFixedStreamMessage<HttpObject> implements HttpResponse {
        ThreeElementFixedHttpResponse(ResponseHeaders headers, HttpObject obj1, HttpObject obj2) {
            super(headers, obj1, obj2);
        }
    }

    static final class RegularFixedHttpResponse
            extends RegularFixedStreamMessage<HttpObject> implements HttpResponse {
        RegularFixedHttpResponse(HttpObject... objs) {
            super(objs);
            checkArgument(objs.length > 0, "There must be at least one ResponseHeaders.");
            checkArgument(objs[0] instanceof ResponseHeaders,
                          "The first HttpObject must be a ResponseHeaders: " + objs[0]);
        }
    }

    private FixedHttpResponse() {}
}

