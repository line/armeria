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

package com.linecorp.armeria.internal.logging;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.FilteredHttpRequest;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

public final class ContentPreviewingUtil {

    /**
     * Sets up the request {@link ContentPreviewer} to set
     * {@link RequestLogBuilder#requestContentPreview(String)} when the preview is available.
     */
    public static HttpRequest setUpRequestContentPreviewer(RequestContext ctx, HttpRequest req,
                                                           ContentPreviewer requestContentPreviewer) {
        requireNonNull(ctx, "ctx");
        requireNonNull(req, "req");
        requireNonNull(requestContentPreviewer, "requestContentPreviewer");
        if (requestContentPreviewer.isDisabled()) {
            return req;
        }

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.deferRequestContentPreview();
        req.whenComplete().handle((unused, unused1) -> {
            // The HttpRequest cannot be subscribed so call requestContentPreview(null) to make sure that the
            // log is complete.
            logBuilder.requestContentPreview(null);
            return null;
        });
        return new FilteredHttpRequest(req) {
            @Override
            protected HttpObject filter(HttpObject obj) {
                if (obj instanceof HttpData) {
                    requestContentPreviewer.onData((HttpData) obj);
                }
                return obj;
            }

            @Override
            protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
                logBuilder.requestContentPreview(requestContentPreviewer.produce());
            }

            @Override
            protected Throwable beforeError(Subscriber<? super HttpObject> subscriber,
                                            Throwable cause) {
                // Call produce() to release the resources in the previewer. Consider adding close() method.
                requestContentPreviewer.produce();

                // Set null to make it sure the log is complete.
                logBuilder.requestContentPreview(null);
                return cause;
            }
        };
    }

    /**
     * Sets up the response {@link ContentPreviewer} to set
     * {@link RequestLogBuilder#responseContentPreview(String)} when the preview is available.
     */
    public static HttpResponse setUpResponseContentPreviewer(
            ContentPreviewerFactory factory, RequestContext ctx, HttpResponse res) {
        requireNonNull(factory, "factory");
        requireNonNull(ctx, "ctx");
        requireNonNull(res, "res");

        return new FilteredHttpResponse(res) {
            @Nullable
            ContentPreviewer responseContentPreviewer;

            @Override
            protected HttpObject filter(HttpObject obj) {
                if (obj instanceof ResponseHeaders) {
                    final ResponseHeaders resHeaders = (ResponseHeaders) obj;

                    // Skip informational headers.
                    final String status = resHeaders.get(HttpHeaderNames.STATUS);
                    if (ArmeriaHttpUtil.isInformational(status)) {
                        return obj;
                    }
                    final ContentPreviewer contentPreviewer = factory.responseContentPreviewer(ctx, resHeaders);
                    if (!contentPreviewer.isDisabled()) {
                        responseContentPreviewer = contentPreviewer;
                    }
                } else if (obj instanceof HttpData) {
                    if (responseContentPreviewer != null) {
                        responseContentPreviewer.onData((HttpData) obj);
                    }
                }
                return obj;
            }

            @Override
            protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
                if (responseContentPreviewer != null) {
                    ctx.logBuilder().responseContentPreview(responseContentPreviewer.produce());
                } else {
                    // Call requestContentPreview(null) to make sure that the log is complete.
                    ctx.logBuilder().responseContentPreview(null);
                }
            }

            @Override
            protected Throwable beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
                if (responseContentPreviewer != null) {
                    // Call produce() to release the resources in the previewer. Consider adding close() method.
                    responseContentPreviewer.produce();
                }
                // Set null to make it sure the log is complete.
                ctx.logBuilder().responseContentPreview(null);
                return cause;
            }
        };
    }

    private ContentPreviewingUtil() {}
}
