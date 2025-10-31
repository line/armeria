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

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MultipartDecodingModeTest {

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
    void utf8() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "multipartFileFoo", "한글.txt"), "qux"));
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/multipart"));
        assertThat(response.contentUtf8()).isEqualTo("한글.txt");
    }

    @Test
    void iso_8859_1IsntDecoded() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "multipartFileFoo", "résumé.pdf"), "qux"));
        final HttpRequest httpRequest = multipart.toHttpRequest("/multipart");
        final AggregatedHttpRequest join = httpRequest.aggregate().join();
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(HttpRequest.of(join.headers(),
                                                                  HttpData.of(StandardCharsets.ISO_8859_1,
                                                                              join.contentUtf8())));
        // Because the default decoding mode is UTF-8, the filename is misdecoded to 'r�sum�.pdf'.
        assertThat(response.contentUtf8()).isNotEqualTo("résumé.pdf");
    }
}
