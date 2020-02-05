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

import java.nio.charset.Charset;
import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;

class TextualContentPreviewerFunction implements Function<HttpHeaders, ContentPreviewer> {

    private static final List<String> subTypeEquals = ImmutableList.of("json", "xml");
    private static final List<String> subTypeEndsWith = ImmutableList.of("+json", "+xml");

    private final int maxLength;
    private final Charset defaultCharset;

    TextualContentPreviewerFunction(int maxLength, Charset defaultCharset) {
        this.maxLength = maxLength;
        this.defaultCharset = defaultCharset;
    }

    @Override
    public ContentPreviewer apply(HttpHeaders headers) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return ContentPreviewer.disabled();
        }
        final Charset charset = contentType.charset();
        if (charset != null) {
            return new LengthLimitingContentPreviewer(maxLength, charset);
        }

        if ("text".equals(contentType.type()) ||
            subTypeEquals.contains(contentType.subtype()) ||
            subTypeEndsWith.stream().anyMatch(contentType.subtype()::endsWith) ||
            contentType.is(MediaType.FORM_DATA)) {
            return new LengthLimitingContentPreviewer(maxLength, defaultCharset);
        }
        return ContentPreviewer.disabled();
    }
}
