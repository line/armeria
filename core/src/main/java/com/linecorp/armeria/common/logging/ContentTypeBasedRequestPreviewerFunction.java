/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import java.util.function.BiFunction;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;

import io.netty.buffer.ByteBuf;

class ContentTypeBasedRequestPreviewerFunction extends ContentTypeBasedPreviewerFunction<RequestHeaders> {

    private final int maxLength;
    private final BiFunction<? super RequestHeaders, ? super ByteBuf, String> requestProducer;

    ContentTypeBasedRequestPreviewerFunction(
            int maxLength, Iterable<MediaType> contentTypes,
            BiFunction<? super RequestHeaders, ? super ByteBuf, String> requestProducer) {
        super(contentTypes);
        this.maxLength = maxLength;
        this.requestProducer = requestProducer;
    }

    @Override
    ContentPreviewer newContentPreviewer(RequestHeaders headers) {
        return new RequestContentPreviewer(maxLength, headers, requestProducer);
    }
}
