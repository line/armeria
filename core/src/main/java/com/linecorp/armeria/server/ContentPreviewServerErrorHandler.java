/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.internal.logging.ContentPreviewingUtil.setUpResponseContentPreviewer;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.logging.ContentPreviewingService;

final class ContentPreviewServerErrorHandler implements DecoratingServerErrorHandlerFunction {

    @Nullable
    @Override
    public HttpResponse onServiceException(ServerErrorHandler delegate,
                                           ServiceRequestContext ctx, Throwable cause) {
        final HttpResponse res = delegate.onServiceException(ctx, cause);

        final ContentPreviewingService contentPreviewingService =
                ctx.findService(ContentPreviewingService.class);
        if (contentPreviewingService == null) {
            return res;
        }

        if (res != null) {
            return setUpResponseContentPreviewer(contentPreviewingService.contentPreviewerFactory(),
                                                 ctx, res,
                                                 contentPreviewingService.responsePreviewSanitizer());
        }
        // Call requestContentPreview(null) to make sure that the log is complete.
        ctx.logBuilder().responseContentPreview(null);
        return res;
    }
}
