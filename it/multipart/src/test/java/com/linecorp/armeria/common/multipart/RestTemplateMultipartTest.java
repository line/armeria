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

package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RestTemplateMultipartTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/multipart/text", (ctx, req) -> {
                return HttpResponse.from(
                        Multipart.from(req).aggregate().thenApply(multiPart -> {
                            final AggregatedBodyPart user = multiPart.field("user");
                            final AggregatedBodyPart org = multiPart.field("org");
                            return HttpResponse.of(org.contentUtf8() + '/' + user.contentUtf8());
                        }));
            });

            sb.service("/multipart/file", (ctx, req) -> {
                return HttpResponse.from(
                        Multipart.from(req).aggregate().thenApply(multiPart -> {
                            final AggregatedBodyPart file = multiPart.field("file");
                            return HttpResponse
                                    .of(file.filename() + '/' + file.name() + '/' + file.contentUtf8().trim());
                        }));
            });
        }
    };

    @Test
    void multipartText() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        final MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("user", "Meri Kim");
        body.add("org", "LINE");

        final HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(body, headers);
        final RestTemplate restTemplate = new RestTemplate();
        final ResponseEntity<String> response =
                restTemplate.postForEntity(server.httpUri().resolve("/multipart/text"), requestEntity,
                                           String.class);

        assertThat(response.getBody()).isEqualTo("LINE/Meri Kim");
    }

    @Test
    void multipartFile() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        final ClassPathResource file = new ClassPathResource("test.txt");
        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);

        final HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        final RestTemplate restTemplate = new RestTemplate();
        final ResponseEntity<String> response =
                restTemplate.postForEntity(server.httpUri().resolve("/multipart/file"), requestEntity,
                                           String.class);

        assertThat(response.getBody()).isEqualTo("test.txt/file/Hello!");
    }
}
