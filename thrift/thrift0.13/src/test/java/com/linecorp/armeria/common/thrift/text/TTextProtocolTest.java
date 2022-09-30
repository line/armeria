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
// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================
package com.linecorp.armeria.common.thrift.text;

import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Base64;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;

import com.linecorp.armeria.common.thrift.text.RpcDebugService.doDebug_args;
import com.linecorp.armeria.common.thrift.text.RpcDebugService.doDebug_result;
import com.linecorp.armeria.internal.common.thrift.TApplicationExceptions;

/**
 * Tests the TTextProtocol.
 *
 * <p>TODO(Alex Roetter): add more tests, especially ones that verify
 * that we generate ParseErrors for invalid input
 *
 * @author Alex Roetter
 */
class TTextProtocolTest {

    private static final Pattern CR_PATTERN = Pattern.compile("(\\\\)+r");

    private String testData;
    private String namedEnumSerialized;
    private Base64 base64Encoder;

    /**
     * Load a file containing a serialized thrift message in from disk.
     */
    @BeforeEach
    void setUp() throws IOException {
        testData = readFile("TTextProtocol_TestData.txt");
        namedEnumSerialized = readFile("TTextProtocol_NamedEnum_Serialized.txt");
        base64Encoder = new Base64();
    }

    /**
     * Read in (deserialize) a thrift message in TTextProtocol format
     * from a file on disk, then serialize it back out to a string.
     * Finally, deserialize that string and compare to the original
     * message.
     */
    @Test
    void tTextProtocolReadWriteTest() throws Exception {
        // Deserialize the file contents into a thrift message.
        final ByteArrayInputStream bais1 = new ByteArrayInputStream(testData.getBytes());
        final TTextProtocolTestMsg msg1 = new TTextProtocolTestMsg();
        msg1.read(new TTextProtocol(new TIOStreamTransport(bais1)));

        assertThat(msg1).isEqualTo(testMsg());

        // Serialize that thrift message out to a byte array
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        msg1.write(new TTextProtocol(new TIOStreamTransport(baos)));
        final byte[] bytes = baos.toByteArray();

        // Deserialize that string back to a thrift message.
        final ByteArrayInputStream bais2 = new ByteArrayInputStream(bytes);
        final TTextProtocolTestMsg msg2 = new TTextProtocolTestMsg();
        msg2.read(new TTextProtocol(new TIOStreamTransport(bais2)));

        assertThat(msg2).isEqualTo(msg1);
    }

    @Test
    void tTextNamedEnumProtocolReadWriteTest() throws Exception {
        // Deserialize the file contents into a thrift message.
        final ByteArrayInputStream bais1 = new ByteArrayInputStream(testData.getBytes());
        final TTextProtocolTestMsg msg1 = new TTextProtocolTestMsg();
        msg1.read(new TTextProtocol(new TIOStreamTransport(bais1), true));

        assertThat(msg1).isEqualTo(testMsg());

        // Serialize that thrift message out to a byte array
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        msg1.write(new TTextProtocol(new TIOStreamTransport(baos), true));
        final byte[] bytes = baos.toByteArray();

        assertThatJson(CR_PATTERN.matcher(baos.toString()).replaceAll(""))
                .when(IGNORING_ARRAY_ORDER)
                .isEqualTo(namedEnumSerialized);

        // Deserialize that string back to a thrift message.
        final ByteArrayInputStream bais2 = new ByteArrayInputStream(bytes);
        final TTextProtocolTestMsg msg2 = new TTextProtocolTestMsg();
        msg2.read(new TTextProtocol(new TIOStreamTransport(bais2), true));

        assertThat(msg2).isEqualTo(msg1);
    }

    private TTextProtocolTestMsg testMsg() {
        return new TTextProtocolTestMsg()
                .setA(12345L)
                .setB(5)
                .setC(sub(1, 10))
                .setD(ImmutableList.of(7, 8, 9, 10, 11))
                .setE(ImmutableList.of(
                        sub(2, 100),
                        sub(3, 200),
                        sub(4, 300)
                ))
                .setF(true)
                .setG((byte) 12)
                .setH(ImmutableMap.of(
                        1, 2L,
                        3, 4L,
                        5, 6L
                ))
                .setJ(ImmutableMap.of(
                        (short) 1, ImmutableList.of(true, true, false, true),
                        (short) 5, ImmutableList.of(false)
                ))
                .setK(ImmutableSet.of(true, false, false, false, true))
                .setL(base64Encoder.decode("SGVsbG8gV29ybGQ="))
                .setM("hello \"spherical\" world!")
                .setN((short) 678)
                .setP(Letter.CHARLIE)
                .setQ(EnumSet.allOf(Letter.class))
                .setR(ImmutableMap.of(sub(1, 2), 100L))
                .setS(ImmutableMap.of(
                        ImmutableMap.of(
                                ImmutableMap.of(200L, 400L), 300L
                        ), 100L
                ))
                .setT(ImmutableList.of(Letter.ALPHA, Letter.ALPHA, Letter.CHARLIE, Letter.ALPHA, Letter.CHARLIE,
                                       Letter.ECHO))
                .setU(ImmutableMap.of("foo", Letter.ALPHA, "bar", Letter.DELTA))
                .setV(Letter.BETA)
                .setW(TestUnion.f2(4))
                .setX(ImmutableList.of(TestUnion.f2(5), TestUnion.f1(base64Encoder.decode("SGVsbG8gV29ybGQ="))))
                .setY(Letter.ALPHA)
                .setAa(ImmutableMap.of(Letter.ALPHA, 2, Letter.BETA, 4))
                .setAb(ImmutableMap.of(Letter.CHARLIE, Number.ONE,
                                       Letter.DELTA, Number.THREE,
                                       Letter.BETA, Number.FIVE))
                .setAc(ImmutableMap.of(
                        ImmutableMap.of(Number.ONE, 3),
                        ImmutableMap.of(new NumberSub(Number.TWO),
                                        ImmutableMap.of("ECHO", ImmutableList.of(Letter.ECHO, Letter.ALPHA,
                                                                                 Letter.ALPHA))),
                        ImmutableMap.of(Number.THREE, 5),
                        ImmutableMap.of(new NumberSub(Number.FOUR),
                                        ImmutableMap.of("ALPHA", ImmutableList.of(Letter.BETA, Letter.DELTA)))
                ));
    }

    private static Sub sub(int s, int x) {
        return new Sub(s, new SubSub(x));
    }

    @Test
    void rpcCall() throws Exception {
        final String request =
                "{\n" +
                "  \"method\" : \"doDebug\",\n" +
                "  \"type\" : \"CALL\",\n" +
                "  \"seqid\" : 1,\n" +
                "  \"args\" : {\n" +
                "    \"methodArg1\" : \"foo1\",\n" +
                "    \"methodArg2\" : 200,\n" +
                "    \"details\" : {\n" +
                "      \"detailsArg1\" : \"foo2\",\n" +
                "      \"detailsArg2\" : 100\n" +
                "    }\n" +
                "  }\n" +
                '}';

        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        final TMessage header = prot.readMessageBegin();
        final doDebug_args args = new RpcDebugService.Processor.doDebug().getEmptyArgsInstance();
        args.read(prot);
        prot.readMessageEnd();

        assertThat(header.name).isEqualTo("doDebug");
        assertThat(header.type).isEqualTo(TMessageType.CALL);
        assertThat(header.seqid).isOne();

        assertThat(args.getMethodArg1()).isEqualTo("foo1");
        assertThat(args.getMethodArg2()).isEqualTo(200);
        assertThat(args.getDetails().getDetailsArg1()).isEqualTo("foo2");
        assertThat(args.getDetails().getDetailsArg2()).isEqualTo(100);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        args.write(prot);
        prot.writeMessageEnd();

        assertThatJson(new String(outputStream.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(request);
    }

    @Test
    void rpcCall_noSeqId() throws Exception {
        final String request =
                "{\n" +
                "  \"method\" : \"doDebug\",\n" +
                "  \"type\" : \"CALL\",\n" +
                "  \"args\" : {\n" +
                "    \"methodArg1\" : \"foo1\",\n" +
                "    \"methodArg2\" : 200,\n" +
                "    \"details\" : {\n" +
                "      \"detailsArg1\" : \"foo2\",\n" +
                "      \"detailsArg2\" : 100\n" +
                "    }\n" +
                "  }\n" +
                '}';

        final TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        final TMessage header = prot.readMessageBegin();
        final doDebug_args args = new RpcDebugService.Processor.doDebug().getEmptyArgsInstance();
        args.read(prot);
        prot.readMessageEnd();

        assertThat(header.name).isEqualTo("doDebug");
        assertThat(header.type).isEqualTo(TMessageType.CALL);
        assertThat(header.seqid).isZero();
    }

    @Test
    void rpcCall_oneWay() throws Exception {
        final String request =
                "{\n" +
                "  \"method\" : \"doDebug\",\n" +
                "  \"type\" : \"ONEWAY\",\n" +
                "  \"seqid\" : 1,\n" +
                "  \"args\" : {\n" +
                "    \"methodArg1\" : \"foo1\",\n" +
                "    \"methodArg2\" : 200,\n" +
                "    \"details\" : {\n" +
                "      \"detailsArg1\" : \"foo2\",\n" +
                "      \"detailsArg2\" : 100\n" +
                "    }\n" +
                "  }\n" +
                '}';

        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        final TMessage header = prot.readMessageBegin();
        final doDebug_args args = new RpcDebugService.Processor.doDebug().getEmptyArgsInstance();
        args.read(prot);
        prot.readMessageEnd();

        assertThat(header.name).isEqualTo("doDebug");
        assertThat(header.type).isEqualTo(TMessageType.ONEWAY);
        assertThat(header.seqid).isEqualTo(1);

        assertThat(args.getMethodArg1()).isEqualTo("foo1");
        assertThat(args.getMethodArg2()).isEqualTo(200);
        assertThat(args.getDetails().getDetailsArg1()).isEqualTo("foo2");
        assertThat(args.getDetails().getDetailsArg2()).isEqualTo(100);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        args.write(prot);
        prot.writeMessageEnd();

        assertThatJson(new String(outputStream.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(request);
    }

    @Test
    void rpcReply() throws Exception {
        final String request =
                "{\n" +
                "  \"method\" : \"doDebug\",\n" +
                "  \"type\" : \"REPLY\",\n" +
                "  \"seqid\" : 100,\n" +
                "  \"args\" : {\n" +
                "    \"success\" : {\n" +
                "      \"response\" : \"Nice response\"\n" +
                "    }\n" +
                "  }\n" +
                '}';

        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        final TMessage header = prot.readMessageBegin();
        final doDebug_result result = new doDebug_result();
        result.read(prot);
        prot.readMessageEnd();

        assertThat(header.name).isEqualTo("doDebug");
        assertThat(header.type).isEqualTo(TMessageType.REPLY);
        assertThat(header.seqid).isEqualTo(100);

        assertThat(result.getSuccess().getResponse()).isEqualTo("Nice response");

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        result.write(prot);
        prot.writeMessageEnd();

        assertThatJson(new String(outputStream.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(request);
    }

    @Test
    void rpcException() throws Exception {
        final String request =
                "{\n" +
                "  \"method\" : \"doDebug\",\n" +
                "  \"type\" : \"EXCEPTION\",\n" +
                "  \"seqid\" : 101,\n" +
                "  \"args\" : {\n" +
                "    \"e\" : {\n" +
                "      \"reason\" : \"Bad rpc\"\n" +
                "    }\n" +
                "  }\n" +
                '}';

        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        final TMessage header = prot.readMessageBegin();
        final doDebug_result result = new doDebug_result();
        result.read(prot);
        prot.readMessageEnd();

        assertThat(header.name).isEqualTo("doDebug");
        assertThat(header.type).isEqualTo(TMessageType.EXCEPTION);
        assertThat(header.seqid).isEqualTo(101);

        assertThat(result.getE().getReason()).isEqualTo("Bad rpc");

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        result.write(prot);
        prot.writeMessageEnd();

        assertThatJson(new String(outputStream.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(request);
    }

    @Test
    void rpcTApplicationException() throws Exception {
        final String request =
                "{\n" +
                "  \"method\" : \"doDebug\",\n" +
                "  \"type\" : \"EXCEPTION\",\n" +
                "  \"seqid\" : 101,\n" +
                "  \"args\" : {\n" +
                "    \"type\" : 4,\n" +
                "    \"message\" : \"bad_seq_id\"\n" +
                "    }\n" +
                "  }\n" +
                '}';

        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        final TMessage header = prot.readMessageBegin();
        final TApplicationException result = TApplicationExceptions.read(prot);
        prot.readMessageEnd();

        assertThat(header.name).isEqualTo("doDebug");
        assertThat(header.type).isEqualTo(TMessageType.EXCEPTION);
        assertThat(header.seqid).isEqualTo(101);

        assertThat(result.getType()).isEqualTo(TApplicationException.BAD_SEQUENCE_ID);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        new TApplicationException(TApplicationException.BAD_SEQUENCE_ID, "bad_seq_id").write(prot);
        prot.writeMessageEnd();

        assertThatJson(new String(outputStream.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(request);
    }

    @Test
    void rpcNoMethod() throws TTransportException {
        final String request =
                "{\n" +
                "  \"type\" : \"CALL\",\n" +
                "  \"args\" : {\n" +
                "    \"methodArg1\" : \"foo1\",\n" +
                "    \"methodArg2\" : 200,\n" +
                "    \"details\" : {\n" +
                "      \"detailsArg1\" : \"foo2\",\n" +
                "      \"detailsArg2\" : 100\n" +
                "    }\n" +
                "  }\n" +
                '}';
        final TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        assertThatThrownBy(prot::readMessageBegin).isInstanceOf(TException.class);
    }

    @Test
    void rpcNoType() throws TTransportException {
        final String request =
                "{\n" +
                "  \"method\" : \"doDebug\",\n" +
                "  \"args\" : {\n" +
                "    \"methodArg1\" : \"foo1\",\n" +
                "    \"methodArg2\" : 200,\n" +
                "    \"details\" : {\n" +
                "      \"detailsArg1\" : \"foo2\",\n" +
                "      \"detailsArg2\" : 100\n" +
                "    }\n" +
                "  }\n" +
                '}';
        final TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        assertThatThrownBy(prot::readMessageBegin).isInstanceOf(TException.class);
    }

    @Test
    void noRpcArgs() throws TTransportException {
        final String request =
                "{\n" +
                "  \"method\" : \"doDebug\"\n" +
                "  \"type\" : \"CALL\",\n" +
                '}';
        final TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        assertThatThrownBy(prot::readMessageBegin).isInstanceOf(TException.class);
    }

    @Test
    void rpcArgsNotObject() throws TTransportException {
        final String request =
                "{\n" +
                "  \"method\" : \"doDebug\",\n" +
                "  \"args\" : 100\n" +
                '}';
        final TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        assertThatThrownBy(prot::readMessageBegin).isInstanceOf(TException.class);
    }

    private static String readFile(String filename) throws IOException {
        return Resources.toString(
                Resources.getResource(
                        TTextProtocolTest.class,
                        "/com/linecorp/armeria/common/thrift/text/" + filename),
                Charsets.UTF_8
        );
    }
}
