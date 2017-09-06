/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.thrift;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import java.io.IOException;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_args;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_result;

public class ThriftStructuredLogJsonFormatTest {
    private static final ObjectMapper customObjectMapper = ThriftStructuredLogJsonFormat.newObjectMapper();

    private static final long TIMESTAMP_MILLIS = 12345;
    private static final long RESPONSE_TIME_NANOS = 6789;
    private static final long REQUEST_SIZE = 128;
    private static final long RESPONSE_SIZE = 512;
    private static final String THRIFT_SERVICE_NAME = HelloService.class.getCanonicalName();
    private static final String THRIFT_METHOD_NAME = "hello";

    private static ThriftStructuredLog buildLog(ThriftCall call, ThriftReply reply) {
        return new ThriftStructuredLog(
                TIMESTAMP_MILLIS,
                RESPONSE_TIME_NANOS,
                REQUEST_SIZE,
                RESPONSE_SIZE,
                THRIFT_SERVICE_NAME,
                THRIFT_METHOD_NAME,
                call, reply);
    }

    @Test
    public void testSerializingRegularFunctionCall() throws IOException {
        ThriftStructuredLog log = buildLog(
                new ThriftCall(
                        new TMessage(THRIFT_METHOD_NAME, TMessageType.CALL, 0),
                        new hello_args().setName("kawamuray")),
                new ThriftReply(
                        new TMessage(THRIFT_METHOD_NAME, TMessageType.REPLY, 0),
                        new hello_result().setSuccess("Hello kawamuray")));

        String actualJson = customObjectMapper.writeValueAsString(log);

        final String expectedJson =
                '{' +
                "    \"timestampMillis\": 12345," +
                "    \"responseTimeNanos\": 6789," +
                "    \"requestSize\": 128," +
                "    \"responseSize\": 512," +
                "    \"thriftServiceName\": \"com.linecorp.armeria.service.test.thrift.main.HelloService\"," +
                "    \"thriftMethodName\": \"hello\"," +
                "    \"thriftCall\": {" +
                "        \"header\": {" +
                "            \"name\": \"hello\"," +
                "            \"type\": 1," +
                "            \"seqid\": 0" +
                "        }," +
                "        \"args\": {" +
                "            \"name\": \"kawamuray\"" +
                "        }" +
                "    }," +
                "    \"thriftReply\": {" +
                "        \"header\": {" +
                "            \"name\": \"hello\"," +
                "            \"type\": 2," +
                "            \"seqid\": 0" +
                "        }," +
                "        \"result\": {" +
                "            \"success\": \"Hello kawamuray\"" +
                "        }," +
                "        \"exception\": null" +
                "    }" +
                '}';

        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void testSerializingExceptionalFunctionCall() throws IOException {
        ThriftStructuredLog log = buildLog(
                new ThriftCall(
                        new TMessage(THRIFT_METHOD_NAME, TMessageType.CALL, 0),
                        new hello_args().setName("kawamuray")),
                new ThriftReply(
                        new TMessage(THRIFT_METHOD_NAME, TMessageType.EXCEPTION, 0),
                        new TApplicationException(1, "don't wanna say hello")));

        String actualJson = customObjectMapper.writeValueAsString(log);

        final String expectedJson =
                '{' +
                "    \"timestampMillis\": 12345," +
                "    \"responseTimeNanos\": 6789," +
                "    \"requestSize\": 128," +
                "    \"responseSize\": 512," +
                "    \"thriftServiceName\": \"com.linecorp.armeria.service.test.thrift.main.HelloService\"," +
                "    \"thriftMethodName\": \"hello\"," +
                "    \"thriftCall\": {" +
                "        \"header\": {" +
                "            \"name\": \"hello\"," +
                "            \"type\": 1," +
                "            \"seqid\": 0" +
                "        }," +
                "        \"args\": {" +
                "            \"name\": \"kawamuray\"" +
                "        }" +
                "    }," +
                "    \"thriftReply\": {" +
                "        \"header\": {" +
                "            \"name\": \"hello\"," +
                "            \"type\": 3," +
                "            \"seqid\": 0" +
                "        }," +
                "        \"result\": null," +
                "        \"exception\": {" +
                "            \"type\": 1," +
                "            \"message\": \"don't wanna say hello\"" +
                "        }" +
                "    }" +
                '}';

        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void testSerializingOnewayFunctionCall() throws IOException {
        ThriftStructuredLog log = buildLog(
                new ThriftCall(
                        new TMessage(THRIFT_METHOD_NAME, TMessageType.ONEWAY, 0),
                        new hello_args().setName("kawamuray")),
                null);

        String actualJson = customObjectMapper.writeValueAsString(log);

        final String expectedJson =
                '{' +
                "    \"timestampMillis\": 12345," +
                "    \"responseTimeNanos\": 6789," +
                "    \"requestSize\": 128," +
                "    \"responseSize\": 512," +
                "    \"thriftServiceName\": \"com.linecorp.armeria.service.test.thrift.main.HelloService\"," +
                "    \"thriftMethodName\": \"hello\"," +
                "    \"thriftCall\": {" +
                "        \"header\": {" +
                "            \"name\": \"hello\"," +
                "            \"type\": 4," +
                "            \"seqid\": 0" +
                "        }," +
                "        \"args\": {" +
                "            \"name\": \"kawamuray\"" +
                "        }" +
                "    }," +
                "    \"thriftReply\": null" +
                '}';

        assertThatJson(actualJson).isEqualTo(expectedJson);
    }
}
