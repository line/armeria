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

import java.util.function.Function;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;

class DefaultContentPreviewerFactory implements ContentPreviewerFactory {

    private final PreviewableRequestPredicate previewableRequestPredicate;
    private final PreviewableResponsePredicate previewableResponsePredicate;
    private final Function<? super RequestHeaders, ? extends ContentPreviewer> requestPreviewerFactory;
    private final Function<? super ResponseHeaders, ? extends ContentPreviewer> responsePreviewerFactory;

    DefaultContentPreviewerFactory(
            PreviewableRequestPredicate previewableRequestPredicate,
            PreviewableResponsePredicate previewableResponsePredicate,
            Function<? super RequestHeaders, ? extends ContentPreviewer> requestPreviewerFactory,
            Function<? super ResponseHeaders, ? extends ContentPreviewer> responsePreviewerFactory) {

        this.previewableRequestPredicate = previewableRequestPredicate;
        this.previewableResponsePredicate = previewableResponsePredicate;
        this.requestPreviewerFactory = requestPreviewerFactory;
        this.responsePreviewerFactory = responsePreviewerFactory;
    }

    @Override
    public ContentPreviewer requestContentPreviewer(RequestContext ctx, RequestHeaders headers) {
        if (!previewableRequestPredicate.test(ctx, headers)) {
            return ContentPreviewer.disabled();
        }

        return requestPreviewerFactory.apply(headers);
    }

    @Override
    public ContentPreviewer responseContentPreviewer(RequestContext ctx, RequestHeaders reqHeaders,
                                                     ResponseHeaders resHeaders) {
        if (!previewableResponsePredicate.test(ctx, reqHeaders, resHeaders)) {
            return ContentPreviewer.disabled();
        }

        return responsePreviewerFactory.apply(resHeaders);
    }
}
