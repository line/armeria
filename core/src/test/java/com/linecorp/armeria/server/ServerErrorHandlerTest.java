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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.testing.AnticipatedException;

class ServerErrorHandlerTest {
    @Test
    void testOrElse() {
        final ServerErrorHandler handler = new ServerErrorHandler() {
            @Nullable
            @Override
            public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
                if (cause instanceof AnticipatedException) {
                    return HttpResponse.of(200);
                }
                return null;
            }
        };

        final ServerErrorHandler orElse = handler.orElse((ctx, cause) -> HttpResponse.of(400));

        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(orElse.onServiceException(ctx, new AnticipatedException()).aggregate().join().status())
                .isSameAs(HttpStatus.OK);
        assertThat(orElse.onServiceException(ctx, new IllegalStateException()).aggregate().join().status())
                .isSameAs(HttpStatus.BAD_REQUEST);
    }
}
