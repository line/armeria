/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DotSegmentTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.decoratorUnder("/admin", (delegate, ctx, req) -> HttpResponse.of(HttpStatus.FORBIDDEN));
        }
    };

    @ParameterizedTest
    @CsvSource({
            "/admin/secret, 403",
            "/./admin/secret, 400",
            "/%2e/admin/secret, 400",
            "/foo/../admin/secret, 400",
            "/.well-known/foo, 200",
    })
    void shouldNotBypassDecoratorWithDotSegments(String path, int status) {
        assertThat(server.blockingWebClient().get(path).status().code()).isEqualTo(status);
    }
}
