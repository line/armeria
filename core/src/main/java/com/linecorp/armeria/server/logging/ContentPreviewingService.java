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

package com.linecorp.armeria.server.logging;

import static com.linecorp.armeria.internal.logging.ContentPreviewingUtil.setUpRequestContentPreviewer;
import static com.linecorp.armeria.internal.logging.ContentPreviewingUtil.setUpResponseContentPreviewer;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

import io.netty.util.AttributeKey;

/**
 * Decorates an {@link HttpService} to preview the content of {@link Request}s and {@link Response}s.
 *
 * <p>Note that this decorator just sets {@link RequestLog#requestContentPreview()} and
 * {@link RequestLog#responseContentPreview()}. You can get the previews using {@link RequestLogAccess}.
 *
 * <pre>{@code
 * RequestLogAccess logAccess = ctx.log();
 * logAccess.whenComplete().thenApply(log -> {
 *     // Call log.requestContentPreview() and log.responseContentPreview() to use them.
 *     ...
 * });
 * }</pre>
 */
public final class ContentPreviewingService extends SimpleDecoratingHttpService {

    private static final AttributeKey<Boolean> SETTING_CONTENT_PREVIEW =
            AttributeKey.valueOf(ContentPreviewingService.class, "SETTING_CONTENT_PREVIEW");

    /**
     * Creates a new {@link ContentPreviewingService} decorator which produces text preview with the
     * specified {@code maxLength} limit. The preview is produced when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} meets any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     *
     * @param maxLength the maximum length of the preview
     */
    public static Function<? super HttpService, ContentPreviewingService> newDecorator(int maxLength) {
        final ContentPreviewerFactory factory = ContentPreviewerFactory.text(maxLength);
        return builder(factory).newDecorator();
    }

    /**
     * Creates a new {@link ContentPreviewingService} decorator which produces text preview with the
     * specified {@code maxLength} limit. The preview is produced when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} meets any of the following conditions:
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
    public static Function<? super HttpService, ContentPreviewingService> newDecorator(
            int maxLength, Charset defaultCharset) {
        final ContentPreviewerFactory factory = ContentPreviewerFactory.text(maxLength, defaultCharset);
        return builder(factory).newDecorator();
    }

    /**
     * Creates a new {@link ContentPreviewingService} decorator with the specified
     * {@link ContentPreviewerFactory}.
     */
    public static Function<? super HttpService, ContentPreviewingService> newDecorator(
            ContentPreviewerFactory contentPreviewerFactory) {
        return builder(contentPreviewerFactory).newDecorator();
    }

    /**
     * Returns a newly-created {@link ContentPreviewingServiceBuilder}.
     */
    public static ContentPreviewingServiceBuilder builder(ContentPreviewerFactory contentPreviewerFactory) {
        return new ContentPreviewingServiceBuilder(
                requireNonNull(contentPreviewerFactory, "contentPreviewerFactory"));
    }

    private final ContentPreviewerFactory contentPreviewerFactory;

    private final BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> requestContentSanitizer;
    private final BiFunction<? super RequestContext, String,
            ? extends @Nullable Object> responseContentSanitizer;

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    ContentPreviewingService(HttpService delegate,
                             ContentPreviewerFactory contentPreviewerFactory,
                             BiFunction<? super RequestContext, String,
                                     ? extends @Nullable Object> requestContentSanitizer,
                             BiFunction<? super RequestContext, String,
                                     ? extends @Nullable Object> responseContentSanitizer) {
        super(delegate);
        this.contentPreviewerFactory = contentPreviewerFactory;
        this.requestContentSanitizer = requestContentSanitizer;
        this.responseContentSanitizer = responseContentSanitizer;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Boolean settingContentPreview = ctx.attr(SETTING_CONTENT_PREVIEW);
        if (Boolean.TRUE.equals(settingContentPreview)) {
            return unwrap().serve(ctx, req);
        }
        ctx.setAttr(SETTING_CONTENT_PREVIEW, true);
        final ContentPreviewer requestContentPreviewer =
                contentPreviewerFactory.requestContentPreviewer(ctx, req.headers());
        req = setUpRequestContentPreviewer(ctx, req, requestContentPreviewer, requestContentSanitizer);

        ctx.logBuilder().defer(RequestLogProperty.RESPONSE_CONTENT_PREVIEW);
        final HttpResponse res = unwrap().serve(ctx, req);
        return setUpResponseContentPreviewer(contentPreviewerFactory, ctx, res, responseContentSanitizer);
    }
}
