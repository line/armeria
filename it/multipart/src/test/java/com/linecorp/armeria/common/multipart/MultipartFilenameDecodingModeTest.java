/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.SetSystemProperty;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@SetSystemProperty(key = "com.linecorp.armeria.defaultMultipartFilenameDecodingMode", value = "URL_DECODING")
class MultipartFilenameDecodingModeTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Post("/multipart")
                public HttpResponse multipart(@Param MultipartFile multipartFileFoo) {
                    return HttpResponse.of(HttpStatus.OK, MediaType.JSON, multipartFileFoo.filename());
                }
            });
        }
    };

    @Test
    void urlDecoding() {
        final String encoded = URLEncoder.encode("한글.txt", StandardCharsets.UTF_8);
        assertThat(encoded).isEqualTo("%ED%95%9C%EA%B8%80.txt");
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "multipartFileFoo", encoded), "qux"));
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/multipart"));
        assertThat(response.contentUtf8()).isEqualTo("한글.txt");
    }
}
