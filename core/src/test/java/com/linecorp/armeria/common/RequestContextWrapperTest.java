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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.google.errorprone.annotations.MustBeClosed;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

class RequestContextWrapperTest {

    static {
        // Armeria properties use bare names.
        Assertions.setExtractBareNamePropertyMethods(true);
    }

    private static final class WrappedRequestContext extends RequestContextWrapper<RequestContext> {
        private WrappedRequestContext(RequestContext delegate) {
            super(delegate);
        }

        // Most wrappers will not want to push the delegate so we don't provide a default implementation of it.
        @Override
        @MustBeClosed
        public SafeCloseable push() {
            return delegate().push();
        }
    }

    @Test
    void wrapMatchesNormal() {
        final RequestContext ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        // Use reflective comparison to handle added properties automatically.
        assertThat(new WrappedRequestContext(ctx)).usingRecursiveComparison().ignoringFields("delegate")
                                                  .isEqualTo(ctx);
    }
}
