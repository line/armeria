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
package com.linecorp.armeria.common.logging;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * A factory creating a {@link ContentPreviewer}.
 */
public interface ContentPreviewerFactory {

    /**
     * Returns a newly created {@link ContentPreviewerFactoryBuilder}.
     */
    static ContentPreviewerFactoryBuilder builder() {
        return new ContentPreviewerFactoryBuilder();
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}.
     * The {@link ContentPreviewer} produces the text with the {@code maxLength} limit
     * if the content type of the {@link RequestHeaders} or {@link ResponseHeaders} meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     *
     * @param maxLength the maximum length of the preview
     */
    static ContentPreviewerFactory ofText(int maxLength) {
        return ofText(maxLength, ContentPreviewerFactoryBuilder.defaultCharset());
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}.
     * The {@link ContentPreviewer} produces the text with the {@code maxLength} limit
     * if the content type of the {@link RequestHeaders} or {@link ResponseHeaders} meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     *
     * @param maxLength the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    static ContentPreviewerFactory ofText(int maxLength, Charset defaultCharset) {
        checkArgument(maxLength > 0, "maxLength : %d (expected: > 0)", maxLength);
        requireNonNull(defaultCharset, "defaultCharset");
        return builder().maxLength(maxLength).textContentType().defaultCharset(defaultCharset).build();
    }

    /**
     * Returns a newly-created {@link ContentPreviewer} with the given {@link RequestContext} and
     * {@link HttpHeaders}. Note that the returned {@link ContentPreviewer} can be
     * {@link ContentPreviewer#disabled()}.
     */
    ContentPreviewer requestContentPreviewer(RequestContext ctx, RequestHeaders headers);

    /**
     * Returns.
     */
    ContentPreviewer responseContentPreviewer(RequestContext ctx,
                                              RequestHeaders reqHeaders, ResponseHeaders resHeaders);
}
