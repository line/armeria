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

package com.linecorp.armeria.server.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DocServiceSettingsTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder("/default", DocService.builder().build());
            sb.serviceUnder("/null", DocService.builder().sensitiveHeaders(null).build());
            sb.serviceUnder("/empty",
                            DocService.builder().sensitiveHeaders(Collections.emptyList()).build());
            sb.serviceUnder("/custom",
                            DocService.builder()
                                      .sensitiveHeaders(ImmutableList.of("Authorization", "X-Api-Key"))
                                      .build());
            sb.serviceUnder("/header-name-constant",
                            DocService.builder()
                                      .sensitiveHeaders(ImmutableList.of(HttpHeaderNames.COOKIE))
                                      .build());
        }
    };

    @Test
    void defaultSensitiveHeaders() throws Exception {
        assertThat(sensitiveHeaders("/default/settings.json")).containsExactly("authorization");
    }

    @Test
    void nullDisablesSensitiveHeaders() throws Exception {
        assertThat(sensitiveHeaders("/null/settings.json")).isEmpty();
    }

    @Test
    void emptyListDisablesSensitiveHeaders() throws Exception {
        assertThat(sensitiveHeaders("/empty/settings.json")).isEmpty();
    }

    @Test
    void customSensitiveHeadersReplaceDefaultAndAreLowerCased() throws Exception {
        assertThat(sensitiveHeaders("/custom/settings.json"))
                .containsExactly("authorization", "x-api-key");
    }

    @Test
    void acceptsHttpHeaderNameConstant() throws Exception {
        assertThat(sensitiveHeaders("/header-name-constant/settings.json"))
                .containsExactly("cookie");
    }

    private static List<String> sensitiveHeaders(String path) throws Exception {
        final JsonNode settings = mapper.readTree(server.blockingWebClient().get(path).contentUtf8());
        final List<String> sensitiveHeaders = new ArrayList<>();
        settings.get("sensitiveHeaders").forEach(value -> sensitiveHeaders.add(value.textValue()));
        return sensitiveHeaders;
    }
}
