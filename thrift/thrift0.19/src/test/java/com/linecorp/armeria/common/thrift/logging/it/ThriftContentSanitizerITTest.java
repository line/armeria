/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.common.thrift.logging.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentSanitizer;
import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.common.thrift.logging.ThriftFieldMaskerSelector;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.SecretService;
import testing.thrift.main.SecretStruct;

class ThriftContentSanitizerITTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void basicCase() throws Exception {
        final ContentSanitizer<String> contentSanitizer = commonContentSanitizer();
        final TestLogWriter logWriter = new TestLogWriter(contentSanitizer);

        server.server().reconfigure(sb -> {
            sb.decorator(LoggingService.builder().logWriter(logWriter).newDecorator());
            sb.service("/", THttpService.builder()
                                        .addService(new SecretService.Iface() {
                                            @Override
                                            public SecretStruct hello(SecretStruct req) throws TException {
                                                return req;
                                            }
                                        })
                                        .build());
        });

        final SecretService.Iface iface = ThriftClients.newClient(server.httpUri(), SecretService.Iface.class);
        final SecretStruct req = new SecretStruct().setSecret("secret").setHello("hello");
        final SecretStruct res = iface.hello(req);
        assertThat(res).isEqualTo(req);

        await().untilAsserted(() -> assertThat(logWriter.blockingDeque()).hasSize(2));

        final String requestLog = logWriter.blockingDeque().takeFirst();
        assertThat(requestLog).contains("hello");
        assertThat(requestLog).doesNotContain("secret");

        final String responseLog = logWriter.blockingDeque().takeFirst();
        assertThat(responseLog).contains("hello");
        assertThat(requestLog).doesNotContain("secret");
    }

    private static ContentSanitizer<String> commonContentSanitizer() {
        return ContentSanitizer.builder()
                               .fieldMaskerSelector(ThriftFieldMaskerSelector.of(info -> {
                                   final Map<String, String> annotations =
                                           info.fieldMetaData().getFieldAnnotations();
                                   if ("red".equals(annotations.get("grade"))) {
                                       return FieldMasker.nullify();
                                   }
                                   return FieldMasker.noMask();
                               }))
                               .buildForText();
    }
}
