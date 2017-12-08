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

import com.linecorp.armeria.common.stream.OneElementFixedStreamMessage;
import com.linecorp.armeria.common.stream.RegularFixedStreamMessage;
import com.linecorp.armeria.common.stream.TwoElementFixedStreamMessage;

/**
 * An {@link HttpResponse} optimized for when all the {@link HttpObject}s that will be published are known at
 * construction time.
 */
final class FixedHttpResponse {

    static final class OneElementFixedHttpResponse
            extends OneElementFixedStreamMessage<HttpObject> implements HttpResponse {
        OneElementFixedHttpResponse(HttpObject obj) {
            super(obj);
        }
    }

    static final class TwoElementFixedHttpResponse
            extends TwoElementFixedStreamMessage<HttpObject> implements HttpResponse {
        TwoElementFixedHttpResponse(HttpObject obj1, HttpObject obj2) {
            super(obj1, obj2);
        }
    }

    static final class RegularFixedHttpResponse
            extends RegularFixedStreamMessage<HttpObject> implements HttpResponse {
        RegularFixedHttpResponse(HttpObject... objs) {
            super(objs);
        }
    }

    private FixedHttpResponse() {}
}

