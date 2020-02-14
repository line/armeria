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
package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AttributeKey;

class RequestContextExporterTest {

    private static final AttributeKey<String> ATTR1 =
            AttributeKey.valueOf(RequestContextExporterTest.class, "ATTR1");
    private static final AttributeKey<String> ATTR2 =
            AttributeKey.valueOf(RequestContextExporterTest.class, "ATTR2");

    @Test
    void shouldNotExportNullValue() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.setAttr(ATTR1, "1");
        ctx.setAttr(ATTR2, null);
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();
        final RequestContextExporter exporter =
                RequestContextExporter.builder()
                                      .addKeyPattern("*")
                                      .addAttribute("attr1", ATTR1)
                                      .addAttribute("attr2", ATTR2)
                                      .build();

        assertThat(exporter.export(ctx)).containsOnlyKeys(
                BuiltInProperty.CLIENT_IP.key,
                BuiltInProperty.ELAPSED_NANOS.key,
                BuiltInProperty.LOCAL_HOST.key,
                BuiltInProperty.LOCAL_IP.key,
                BuiltInProperty.LOCAL_PORT.key,
                BuiltInProperty.REMOTE_HOST.key,
                BuiltInProperty.REMOTE_IP.key,
                BuiltInProperty.REMOTE_PORT.key,
                BuiltInProperty.REQ_AUTHORITY.key,
                BuiltInProperty.REQ_CONTENT_LENGTH.key,
                BuiltInProperty.REQ_DIRECTION.key,
                BuiltInProperty.REQ_METHOD.key,
                BuiltInProperty.REQ_PATH.key,
                BuiltInProperty.RES_CONTENT_LENGTH.key,
                BuiltInProperty.RES_STATUS_CODE.key,
                BuiltInProperty.SCHEME.key,
                "attrs.attr1");
    }
}
