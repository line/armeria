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
package com.linecorp.armeria.common.thrift;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import java.io.IOException;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import testing.thrift.main.HelloService.hello_args;
import testing.thrift.main.HelloService.hello_result;

class ThriftJacksonTest {
    private static final ObjectMapper defaultMapper = new ObjectMapper();
    private static final ObjectMapper customMapper = new ObjectMapper();

    static {
        customMapper.registerModule(new ThriftJacksonModule());
    }

    private static final String THRIFT_METHOD_NAME = "hello";

    @Test
    void serializeThriftCall() throws IOException {
        final ThriftCall call = new ThriftCall(
                new TMessage(THRIFT_METHOD_NAME, TMessageType.CALL, 0),
                new hello_args().setName("kawamuray"));
        final String actualJson = defaultMapper.writeValueAsString(call);
        final String actualJson2 = customMapper.writeValueAsString(call);

        assertThatJson(actualJson).isEqualTo(
                '{' +
                "    \"header\": {" +
                "        \"name\": \"hello\"," +
                "        \"type\": 1," +
                "        \"seqid\": 0" +
                "    }," +
                "    \"args\": {" +
                "        \"name\": \"kawamuray\"" +
                "    }" +
                '}');
        assertThatJson(actualJson2).isEqualTo(actualJson);
    }

    @Test
    void serializeThriftReply() throws IOException {
        final ThriftReply reply = new ThriftReply(
                new TMessage(THRIFT_METHOD_NAME, TMessageType.REPLY, 0),
                new hello_result().setSuccess("Hello kawamuray"));
        final String actualJson = defaultMapper.writeValueAsString(reply);
        final String actualJson2 = customMapper.writeValueAsString(reply);

        assertThatJson(actualJson).isEqualTo(
                '{' +
                "    \"header\": {" +
                "        \"name\": \"hello\"," +
                "        \"type\": 2," +
                "        \"seqid\": 0" +
                "    }," +
                "    \"result\": {" +
                "        \"success\": \"Hello kawamuray\"" +
                "    }," +
                "    \"exception\": null" +
                '}');
        assertThatJson(actualJson2).isEqualTo(actualJson);
    }

    @Test
    void serializeThriftReplyWithException() throws IOException {
        final ThriftReply reply = new ThriftReply(
                new TMessage(THRIFT_METHOD_NAME, TMessageType.EXCEPTION, 0),
                new TApplicationException(1, "don't wanna say hello"));
        final String actualJson = defaultMapper.writeValueAsString(reply);
        final String actualJson2 = customMapper.writeValueAsString(reply);

        assertThatJson(actualJson).isEqualTo(
                '{' +
                "    \"header\": {" +
                "        \"name\": \"hello\"," +
                "        \"type\": 3," +
                "        \"seqid\": 0" +
                "    }," +
                "    \"result\": null," +
                "    \"exception\": {" +
                "        \"type\": 1," +
                "        \"message\": \"don't wanna say hello\"" +
                "    }" +
                '}');
        assertThatJson(actualJson2).isEqualTo(actualJson);
    }

    @Test
    void serializeTMessage() throws IOException {
        assertThatJson(customMapper.writeValueAsString(new TMessage(THRIFT_METHOD_NAME,
                                                                    TMessageType.EXCEPTION, 0)))
                .isEqualTo('{' +
                           "    \"name\": \"hello\"," +
                           "    \"type\": 3," +
                           "    \"seqid\": 0" +
                           '}');
    }

    @Test
    void serializeTBase() throws IOException {
        assertThatJson(customMapper.writeValueAsString(new hello_args().setName("kawamuray")))
                .isEqualTo('{' +
                           "    \"name\": \"kawamuray\"" +
                           '}');
    }

    @Test
    void serializeTApplicationException() throws IOException {
        assertThatJson(customMapper.writeValueAsString(new TApplicationException(1, "don't wanna say hello")))
                .isEqualTo('{' +
                           "    \"type\": 1," +
                           "    \"message\": \"don't wanna say hello\"" +
                           '}');
    }
}
