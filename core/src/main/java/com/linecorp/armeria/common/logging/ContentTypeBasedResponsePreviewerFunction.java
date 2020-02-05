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
import com.linecorp.armeria.common.ResponseHeaders;

import io.netty.buffer.ByteBuf;

class ContentTypeBasedResponsePreviewerFunction extends ContentTypeBasedPreviewerFunction<ResponseHeaders> {

    private final int maxLength;
    private final BiFunction<? super ResponseHeaders, ? super ByteBuf, String> responseProducer;

    ContentTypeBasedResponsePreviewerFunction(
            int maxLength, Iterable<MediaType> contentTypes,
            BiFunction<? super ResponseHeaders, ? super ByteBuf, String> responseProducer) {
        super(contentTypes);
        this.maxLength = maxLength;
        this.responseProducer = responseProducer;
    }

    @Override
    ContentPreviewer newContentPreviewer(ResponseHeaders headers) {
        return new ResponseContentPreviewer(maxLength, headers, responseProducer);
    }
}
