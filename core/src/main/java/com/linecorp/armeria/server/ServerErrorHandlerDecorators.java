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

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

final class ServerErrorHandlerDecorators {

    private static final List<DecoratingServerErrorHandlerFunction> decoratorFunctions =
            ImmutableList.of(new CorsServerErrorHandler(),
                             new ContentPreviewServerErrorHandler());

    private ServerErrorHandlerDecorators() {}

    static ServerErrorHandler decorate(ServerErrorHandler delegate) {
        ServerErrorHandler decorated = delegate;
        for (DecoratingServerErrorHandlerFunction decoratorFunction : decoratorFunctions) {
            decorated = new DecoratingServerErrorHandler(decorated, decoratorFunction);
        }
        return decorated;
    }

    private static final class DecoratingServerErrorHandler implements ServerErrorHandler {

        final ServerErrorHandler delegate;
        final DecoratingServerErrorHandlerFunction decoratorFunction;

        DecoratingServerErrorHandler(ServerErrorHandler delegate,
                                     DecoratingServerErrorHandlerFunction decoratorFunction) {
            this.delegate = delegate;
            this.decoratorFunction = decoratorFunction;
        }

        @Nullable
        @Override
        public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
            return decoratorFunction.onServiceException(delegate, ctx, cause);
        }

        @Nullable
        @Override
        public AggregatedHttpResponse onProtocolViolation(ServiceConfig config,
                                                          @Nullable RequestHeaders headers,
                                                          HttpStatus status,
                                                          @Nullable String description,
                                                          @Nullable Throwable cause) {
            return delegate.onProtocolViolation(config, headers, status, description, cause);
        }

        @Nullable
        @Override
        public AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                                   ServiceConfig config,
                                                   @Nullable RequestHeaders headers,
                                                   HttpStatus status, @Nullable String description,
                                                   @Nullable Throwable cause) {
            return delegate.renderStatus(ctx, config, headers, status, description, cause);
        }
    }
}
