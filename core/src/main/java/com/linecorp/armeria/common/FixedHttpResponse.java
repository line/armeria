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

import com.linecorp.armeria.common.stream.FixedStreamMessage;

/**
 * An {@link HttpResponse} optimized for when all the {@link HttpObject}s that will be published are known at
 * construction time.
 */
public final class FixedHttpResponse extends FixedStreamMessage<HttpObject> implements HttpResponse {

    /**
     * Creates a new {@link FixedHttpResponse} that will publish the given {@code objs}. {@code objs} is not
     * copied so must not be mutated after this method call (it is generally meant to be used with a varargs
     * invocation). The first element of {@code objs} must be of type {@link HttpHeaders}.
     */
    public static FixedHttpResponse of(HttpObject... objs) {
        requireNonNull(objs, "objs");
        checkArgument(objs.length > 0, "At least one HttpObject must be published.");
        checkArgument(objs[0] instanceof HttpHeaders, "First published object must be headers.");
        return new FixedHttpResponse(objs);
    }

    private FixedHttpResponse(HttpObject[] objs) {
        super(objs);
    }
}
