/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.internal.logging.ContentPreviewingUtil.setUpRequestContentPreviewer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Functions;
import com.linecorp.armeria.server.ServiceRequestContext;

class ContentPreviewingUtilTest {

    @Test
    void abortedRequestShouldAlsoBeCompleted() {
        final HttpRequestWriter req = HttpRequest.streaming(RequestHeaders.builder(HttpMethod.POST, "/")
                                                                          .contentType(MediaType.PLAIN_TEXT)
                                                                          .build());
        req.write(HttpData.ofUtf8("Armeria"));
        req.abort(new RuntimeException());
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        final ContentPreviewer contentPreviewer =
                ContentPreviewerFactory.text(1024).requestContentPreviewer(ctx, req.headers());

        final HttpRequest filteredReq = setUpRequestContentPreviewer(ctx, req,
                                                                     contentPreviewer,
                                                                     Functions.second());
        assertThat(ctx.logBuilder().isDeferred(RequestLogProperty.REQUEST_CONTENT_PREVIEW)).isTrue();

        try {
            filteredReq.aggregate().join();
            fail("aborted request should fail aggregate().join()");
        } catch (Exception e) {
            // ignored
        }

        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();
        ctx.logBuilder().ensureComplete();

        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.requestContentPreview()).isEmpty();
    }
}
