/*
 * Copyright 2015 LINE Corporation
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

import static net.javacrumbs.jsonunit.JsonAssert.assertJsonEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import org.apache.commons.codec.binary.Base64;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.transport.TIOStreamTransport;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;

import com.linecorp.armeria.common.thrift.text.RpcDebugService.doDebug_args;
import com.linecorp.armeria.common.thrift.text.RpcDebugService.doDebug_result;
import com.linecorp.armeria.internal.thrift.TApplicationExceptions;

/**
 * Tests the TTextProtocol.
 *
 * <p>TODO(Alex Roetter): add more tests, especially ones that verify
 * that we generate ParseErrors for invalid input
 *
 * @author Alex Roetter
 */
public class TTextProtocolTest {

    private String fileContents;
    private Base64 base64Encoder;

    /**
     * Load a file containing a serialized thrift message in from disk.
     */
    @Before
    public void setUp() throws IOException {
        fileContents = Resources.toString(
                Resources.getResource(
                        getClass(),
                        "/com/linecorp/armeria/common/thrift/text/TTextProtocol_TestData.txt"),
                Charsets.UTF_8);

        base64Encoder = new Base64();
    }

    /**
     * Read in (deserialize) a thrift message in TTextProtocol format
     * from a file on disk, then serialize it back out to a string.
     * Finally, deserialize that string and compare to the original
     * message.
     */
    @Test
    public void tTextProtocolReadWriteTest() throws Exception {
        // Deserialize the file contents into a thrift message.
        ByteArrayInputStream bais1 = new ByteArrayInputStream(
                fileContents.getBytes());

        TTextProtocolTestMsg msg1 = new TTextProtocolTestMsg();
        msg1.read(new TTextProtocol(new TIOStreamTransport(bais1)));

        assertEquals(testMsg(), msg1);

        // Serialize that thrift message out to a byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        msg1.write(new TTextProtocol(new TIOStreamTransport(baos)));
        byte[] bytes = baos.toByteArray();

        // Deserialize that string back to a thrift message.
        ByteArrayInputStream bais2 = new ByteArrayInputStream(bytes);
        TTextProtocolTestMsg msg2 = new TTextProtocolTestMsg();
        msg2.read(new TTextProtocol(new TIOStreamTransport(bais2)));

        assertEquals(msg1, msg2);
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
                .setY(Letter.ALPHA);
    }

    private static Sub sub(int s, int x) {
        return new Sub(s, new SubSub(x));
    }

    @Test
    public void rpcCall() throws Exception {
        String request =
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
        TMessage header = prot.readMessageBegin();
        doDebug_args args = new RpcDebugService.Processor.doDebug().getEmptyArgsInstance();
        args.read(prot);
        prot.readMessageEnd();

        assertEquals("doDebug", header.name);
        assertEquals(TMessageType.CALL, header.type);
        assertEquals(1, header.seqid);

        assertEquals("foo1", args.getMethodArg1());
        assertEquals(200, args.getMethodArg2());
        assertEquals("foo2", args.getDetails().getDetailsArg1());
        assertEquals(100, args.getDetails().getDetailsArg2());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        args.write(prot);
        prot.writeMessageEnd();

        assertJsonEquals(request, new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void rpcCall_noSeqId() throws Exception {
        String request =
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

        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        TMessage header = prot.readMessageBegin();
        doDebug_args args = new RpcDebugService.Processor.doDebug().getEmptyArgsInstance();
        args.read(prot);
        prot.readMessageEnd();

        assertEquals("doDebug", header.name);
        assertEquals(TMessageType.CALL, header.type);
        assertEquals(0, header.seqid);
    }

    @Test
    public void rpcCall_oneWay() throws Exception {
        String request =
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
        TMessage header = prot.readMessageBegin();
        doDebug_args args = new RpcDebugService.Processor.doDebug().getEmptyArgsInstance();
        args.read(prot);
        prot.readMessageEnd();

        assertEquals("doDebug", header.name);
        assertEquals(TMessageType.ONEWAY, header.type);
        assertEquals(1, header.seqid);

        assertEquals("foo1", args.getMethodArg1());
        assertEquals(200, args.getMethodArg2());
        assertEquals("foo2", args.getDetails().getDetailsArg1());
        assertEquals(100, args.getDetails().getDetailsArg2());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        args.write(prot);
        prot.writeMessageEnd();

        assertJsonEquals(request, new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void rpcReply() throws Exception {
        String request =
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
        TMessage header = prot.readMessageBegin();
        doDebug_result result = new doDebug_result();
        result.read(prot);
        prot.readMessageEnd();

        assertEquals("doDebug", header.name);
        assertEquals(TMessageType.REPLY, header.type);
        assertEquals(100, header.seqid);

        assertEquals("Nice response", result.getSuccess().getResponse());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        result.write(prot);
        prot.writeMessageEnd();

        assertJsonEquals(request, new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void rpcException() throws Exception {
        String request =
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
        TMessage header = prot.readMessageBegin();
        doDebug_result result = new doDebug_result();
        result.read(prot);
        prot.readMessageEnd();

        assertEquals("doDebug", header.name);
        assertEquals(TMessageType.EXCEPTION, header.type);
        assertEquals(101, header.seqid);

        assertEquals("Bad rpc", result.getE().getReason());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        result.write(prot);
        prot.writeMessageEnd();

        assertJsonEquals(request, new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void rpcTApplicationException() throws Exception {
        String request =
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
        TMessage header = prot.readMessageBegin();
        TApplicationException result = TApplicationExceptions.read(prot);
        prot.readMessageEnd();

        assertEquals("doDebug", header.name);
        assertEquals(TMessageType.EXCEPTION, header.type);
        assertEquals(101, header.seqid);

        assertEquals(TApplicationException.BAD_SEQUENCE_ID, result.getType());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        prot = new TTextProtocol(new TIOStreamTransport(outputStream));
        prot.writeMessageBegin(header);
        new TApplicationException(TApplicationException.BAD_SEQUENCE_ID, "bad_seq_id").write(prot);
        prot.writeMessageEnd();

        assertJsonEquals(request, new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test(expected = TException.class)
    public void rpcNoMethod() throws Exception {
        String request =
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
        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        prot.readMessageBegin();
    }

    @Test(expected = TException.class)
    public void rpcNoType() throws Exception {
        String request =
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
        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        prot.readMessageBegin();
    }

    @Test(expected = TException.class)
    public void noRpcArgs() throws Exception {
        String request =
                "{\n" +
                "  \"method\" : \"doDebug\"\n" +
                "  \"type\" : \"CALL\",\n" +
                '}';
        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        prot.readMessageBegin();
    }

    @Test(expected = TException.class)
    public void rpcArgsNotObject() throws Exception {
        String request =
                "{\n" +
                "  \"method\" : \"doDebug\",\n" +
                "  \"args\" : 100\n" +
                '}';
        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        prot.readMessageBegin();
    }
}
