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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JsonConditionalResponseAsTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws JsonProcessingException {
            sb.service("/json", (ctx, req) -> HttpResponse.ofJson(new MyMessage("OK")));
            sb.service("/json_bad_request", (ctx, req) ->
                    HttpResponse.ofJson(HttpStatus.BAD_REQUEST, new MyMessage("BadRequest"))
            );
            sb.service("/json_server_error", (ctx, req) ->
                    HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR, new MyMessage("ServerError"))
            );

            sb.service("/json_mapper", (ctx, req) -> HttpResponse.of("{ 'id': 200 }"));
            sb.service("/json_mapper_bad_request", (ctx, req) -> HttpResponse.of(
                    HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8, "{ 'id': 400 }"));
            sb.service("/json_mapper_server_error", (ctx, req) -> HttpResponse.of(
                    HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, "{ 'id': 500 }"));

            final MyObject myOkObject = new MyObject();
            myOkObject.setId(200);
            sb.service("/json_generic", (ctx, req) ->
                    HttpResponse.ofJson(ImmutableList.of(myOkObject)));
            final MyObject myBadRequestObject = new MyObject();
            myBadRequestObject.setId(400);
            sb.service("/json_generic_bad_request", (ctx, req) ->
                    HttpResponse.ofJson(HttpStatus.BAD_REQUEST, ImmutableList.of(myBadRequestObject)));
            final MyObject myServerErrorObject = new MyObject();
            myServerErrorObject.setId(500);
            sb.service("/json_generic_server_error", (ctx, req) ->
                    HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR,
                                        ImmutableList.of(myServerErrorObject)));

            sb.service("/json_generic_mapper", (ctx, req) ->
                    HttpResponse.of("[{ 'id': 200 }]"));
            sb.service("/json_generic_mapper_bad_request", (ctx, req) -> HttpResponse.of(
                    HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8, "[{ 'id': 400 }]"));
            sb.service("/json_generic_mapper_server_error", (ctx, req) -> HttpResponse.of(
                    HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, "[{ 'id': 500 }]"));
        }
    };

    interface MyResponse {}

    static class MyObject implements MyResponse {

        private int id;

        public void setId(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MyObject)) {
                return false;
            }
            final MyObject myObject = (MyObject) o;
            return id == myObject.id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }

    static final class EmptyMessage implements MyResponse {}

    static final class MyMessage implements MyResponse {
        @JsonProperty("message")
        private final String message;

        @JsonCreator
        MyMessage(@JsonProperty("message") String message) {
            this.message = message;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MyMessage)) {
                return false;
            }

            final MyMessage
                    myMessage = (MyMessage) o;
            return message.equals(myMessage.message);
        }

        @Override
        public int hashCode() {
            return message.hashCode();
        }
    }

    static final class MyError implements MyResponse {
        private final String code;
        private final String body;

        private MyError(String code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    @Test
    void jsonObject_orElseJson() {
        final MyMessage myServerErrorMessage = new MyMessage("ServerError");
        final ResponseEntity<MyResponse> errorResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_server_error")
                         .as(ResponseAs.<MyResponse>json(MyMessage.class,
                                                         res -> res.status().isServerError())
                                       .orElseJson(MyMessage.class, res -> res.status().isClientError())
                                       .orElseJson(MyMessage.class)).execute().join();
        assertThat(errorResponseEntity.content()).isEqualTo(myServerErrorMessage);

        final MyMessage myBadRequestMessage = new MyMessage("BadRequest");
        final ResponseEntity<MyResponse> badRequestResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_bad_request")
                         .as(ResponseAs.<MyResponse>json(MyMessage.class,
                                                         res -> res.status().isServerError())
                                       .orElseJson(MyMessage.class, res -> res.status().isClientError())
                                       .orElseJson(MyMessage.class)).execute().join();
        assertThat(badRequestResponseEntity.content()).isEqualTo(myBadRequestMessage);

        final MyMessage myOkMessage = new MyMessage("OK");
        final ResponseEntity<MyResponse> okResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json")
                         .as(ResponseAs.<MyResponse>json(MyMessage.class,
                                                         res -> res.status().isServerError())
                                       .orElseJson(MyMessage.class, res -> res.status().isClientError())
                                       .orElseJson(MyMessage.class)).execute().join();
        assertThat(okResponseEntity.content()).isEqualTo(myOkMessage);
    }

    @Test
    void jsonObject_customMapper_orElseJson() {
        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();

        final MyObject myServerErrorObject = new MyObject();
        myServerErrorObject.setId(500);
        final ResponseEntity<MyResponse> errorResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_mapper_server_error")
                         .as(ResponseAs.<MyResponse>json(MyObject.class, mapper,
                                                         res -> res.status().isServerError())
                                       .orElseJson(MyObject.class, mapper, res -> res.status().isClientError())
                                       .orElseJson(MyObject.class, mapper)).execute().join();
        assertThat(errorResponseEntity.content()).isEqualTo(myServerErrorObject);

        final MyObject myBadRequestObject = new MyObject();
        myBadRequestObject.setId(400);
        final ResponseEntity<MyResponse> badRequestResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_mapper_bad_request")
                         .as(ResponseAs.<MyResponse>json(MyObject.class, mapper,
                                                         res -> res.status().isServerError())
                                       .orElseJson(MyObject.class, mapper, res -> res.status().isClientError())
                                       .orElseJson(MyObject.class, mapper)).execute().join();
        assertThat(badRequestResponseEntity.content()).isEqualTo(myBadRequestObject);

        final MyObject myOkObject = new MyObject();
        myOkObject.setId(200);
        final ResponseEntity<MyResponse> okResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_mapper")
                         .as(ResponseAs.<MyResponse>json(MyObject.class, mapper,
                                                         res -> res.status().isServerError())
                                       .orElseJson(MyObject.class, mapper, res -> res.status().isClientError())
                                       .orElseJson(MyObject.class, mapper)).execute().join();
        assertThat(okResponseEntity.content()).isEqualTo(myOkObject);
    }

    @Test
    void jsonGeneric_orElseJson() {
        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();

        final MyObject myServerErrorObject = new MyObject();
        myServerErrorObject.setId(500);
        final ResponseEntity<List<MyObject>> ServerErrorResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_generic_mapper_server_error")
                         .as(ResponseAs.json(new TypeReference<List<MyObject>>() {}, mapper,
                                             res -> res.status().isServerError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {}, mapper,
                                                    res -> res.status().isClientError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {}, mapper))
                         .execute().join();
        assertThat(ServerErrorResponseEntity.content()).isEqualTo(ImmutableList.of(myServerErrorObject));

        final MyObject myBadRequestObject = new MyObject();
        myBadRequestObject.setId(400);
        final ResponseEntity<List<MyObject>> BadRequestResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_generic_mapper_bad_request")
                         .as(ResponseAs.json(new TypeReference<List<MyObject>>() {}, mapper,
                                             res -> res.status().isServerError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {}, mapper,
                                                    res -> res.status().isClientError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {}, mapper))
                         .execute().join();
        assertThat(BadRequestResponseEntity.content()).isEqualTo(ImmutableList.of(myBadRequestObject));

        final MyObject myOkObject = new MyObject();
        myOkObject.setId(200);
        final ResponseEntity<List<MyObject>> OkResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_generic_mapper")
                         .as(ResponseAs.json(new TypeReference<List<MyObject>>() {}, mapper,
                                             res -> res.status().isServerError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {}, mapper,
                                                    res -> res.status().isClientError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {}, mapper))
                         .execute().join();
        assertThat(OkResponseEntity.content()).isEqualTo(ImmutableList.of(myOkObject));
    }

    @Test
    void jsonGeneric_customMapper_orElseJson() {
        final MyObject myServerErrorObject = new MyObject();
        myServerErrorObject.setId(500);
        final ResponseEntity<List<MyObject>> ServerErrorResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_generic_server_error")
                         .as(ResponseAs.json(new TypeReference<List<MyObject>>() {},
                                             res -> res.status().isServerError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {},
                                                    res -> res.status().isClientError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {}))
                         .execute().join();
        assertThat(ServerErrorResponseEntity.content()).isEqualTo(ImmutableList.of(myServerErrorObject));

        final MyObject myBadRequestObject = new MyObject();
        myBadRequestObject.setId(400);
        final ResponseEntity<List<MyObject>> BadRequestResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_generic_bad_request")
                         .as(ResponseAs.json(new TypeReference<List<MyObject>>() {},
                                             res -> res.status().isServerError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {},
                                                    res -> res.status().isClientError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {}))
                         .execute().join();
        assertThat(BadRequestResponseEntity.content()).isEqualTo(ImmutableList.of(myBadRequestObject));

        final MyObject myOkObject = new MyObject();
        myOkObject.setId(200);
        final ResponseEntity<List<MyObject>> OkResponseEntity =
                WebClient.of(server.httpUri()).prepare().get("/json_generic")
                         .as(ResponseAs.json(new TypeReference<List<MyObject>>() {},
                                             res -> res.status().isServerError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {},
                                                    res -> res.status().isClientError())
                                       .orElseJson(new TypeReference<List<MyObject>>() {}))
                         .execute().join();
        assertThat(OkResponseEntity.content()).isEqualTo(ImmutableList.of(myOkObject));
    }

    @Test
    void json_restClient() {
        final ResponseEntity<MyResponse> responseEntity =
                RestClient.of(server.httpUri()).get("/json_server_error")
                          .execute(ResponseAs.<MyResponse>json(
                                                     MyMessage.class, res -> res.status().isError())
                                             .orElseJson(
                                                     EmptyMessage.class, res -> res.status().isInformational())
                                             .orElseJson(MyError.class)).join();
        assertThat(responseEntity.content()).isEqualTo(new MyMessage("ServerError"));
    }
}
