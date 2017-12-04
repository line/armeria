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
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.stream.FixedStreamMessage.EmptyFixedStreamMessage;
import com.linecorp.armeria.common.stream.FixedStreamMessage.OneElementFixedStreamMessage;
import com.linecorp.armeria.common.stream.FixedStreamMessage.RegularFixedStreamMessage;
import com.linecorp.armeria.common.stream.FixedStreamMessage.TwoElementFixedStreamMessage;

/**
 * An {@link HttpResponse} optimized for when all the {@link HttpObject}s that will be published are known at
 * construction time.
 */
public final class FixedHttpResponse {

    /**
     * Creates a new {@link HttpResponse} that will not publish anything.
     */
    public static HttpResponse of() {
        return new EmptyFixedHttpResponse();
    }

    /**
     * Creates a new {@link HttpResponse} that will publish the given {@link HttpHeaders}.
     */
    public static HttpResponse of(HttpHeaders obj) {
        requireNonNull(obj, "obj");
        return new OneElementFixedHttpResponse(obj);
    }

    /**
     * Creates a new {@link HttpResponse} that will publish the given {@link HttpHeaders} and
     * {@link HttpObject}.
     */
    public static HttpResponse of(HttpHeaders obj1, HttpObject obj2) {
        requireNonNull(obj1, "obj1");
        requireNonNull(obj2, "obj2");
        return new TwoElementFixedHttpResponse(obj1, obj2);
    }

    /**
     * Creates a new {@link HttpResponse} that will publish the given {@code objs}. {@code objs} is not
     * copied so must not be mutated after this method call (it is generally meant to be used with a varargs
     * invocation). The first element of {@code objs} must be of type {@link HttpHeaders}.
     */
    public static HttpResponse of(HttpObject... objs) {
        requireNonNull(objs, "objs");
        if (objs.length == 0) {
            return of();
        }
        checkArgument(objs[0] instanceof HttpHeaders, "First published object must be headers.");
        HttpHeaders headers = (HttpHeaders) objs[0];
        switch (objs.length) {
            case 1:
                return of(headers);
            case 2:
                return of(headers, objs[1]);
            default:
                return new RegularFixedHttpResponse(objs);
        }
    }

    private static final class EmptyFixedHttpResponse
            extends EmptyFixedStreamMessage<HttpObject> implements HttpResponse {}

    private static final class OneElementFixedHttpResponse
            extends OneElementFixedStreamMessage<HttpObject> implements HttpResponse {
        private OneElementFixedHttpResponse(HttpObject obj) {
            super(obj);
        }
    }

    private static final class TwoElementFixedHttpResponse
            extends TwoElementFixedStreamMessage<HttpObject> implements HttpResponse {
        private TwoElementFixedHttpResponse(HttpObject obj1, HttpObject obj2) {
            super(obj1, obj2);
        }
    }

    private static final class RegularFixedHttpResponse
            extends RegularFixedStreamMessage<HttpObject> implements HttpResponse {
        private RegularFixedHttpResponse(HttpObject[] objs) {
            super(objs);
        }
    }

    private FixedHttpResponse() {}
}

