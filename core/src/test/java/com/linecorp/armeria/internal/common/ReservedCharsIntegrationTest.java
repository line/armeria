/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ReservedCharsIntegrationTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder("/", (ctx, req) -> {
                return HttpResponse.ofJson(
                        ImmutableMap.of("path", ctx.path(), "query", firstNonNull(ctx.query(), "")));
            });
        }
    };

    @Test
    void shouldPreserveReservedCharsInPath() {
        final Map<String, String> response =
                server.blockingWebClient().prepare()
                      // %2F = '/', %3F = '?'
                      .get("/%2F%3F/foo?bar=1")
                      .asJson(new TypeReference<Map<String, String>>() {})
                      .execute()
                      .content();
        assertThat(response.get("path")).isEqualTo("/%2F%3F/foo");
        assertThat(response.get("query")).isEqualTo("bar=1");
    }
}
