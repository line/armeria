// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================

package com.linecorp.armeria.common.thrift.text;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
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

/**
 * Test the TTextProtocol
 *
 * TODO(Alex Roetter): add more tests, especially ones that verify
 * that we generate ParseErrors for invalid input
 *
 * @author Alex Roetter
 */
public class TTextProtocolTest {

    private String fileContents;
    private Base64 base64Encoder;

    /**
     * Load a file containing a serialized thrift message in from disk
     * @throws IOException
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
     * @throws IOException
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
                .setH(ImmutableMap.<Integer, Long>of(
                        1, 2L,
                        3, 4L,
                        5, 6L
                ))
                .setJ(ImmutableMap.<Short,List<Boolean>>of(
                        (short) 1, ImmutableList.<Boolean>of(true, true, false, true),
                        (short) 5, ImmutableList.<Boolean>of(false)
                ))
                .setK(ImmutableSet.of(true, false, false, false, true))
                .setL(base64Encoder.decode("SGVsbG8gV29ybGQ="))
                .setM("hello \"spherical\" world!")
                .setN((short) 678)
                .setP(Letter.CHARLIE)
                .setQ(EnumSet.allOf(Letter.class))
                .setR(ImmutableMap.<Sub, Long>of(sub(1, 2), 100L))
                .setS(ImmutableMap.<Map<Map<Long, Long>, Long> ,Long>of(
                        ImmutableMap.<Map<Long, Long>, Long>of(
                                ImmutableMap.<Long, Long>of(200L, 400L), 300L
                        ), 100L
                ))
                ;

    }

    private Sub sub(int s, int x) {
        return new Sub(s, new SubSub(x));
    }

    // For TUnion structure, TTextProtocol can only handle serialization, but not deserialization.
    // Because when deserialization, we loose the context of which thrift class we are currently at.
    // Specifically, because we rely on the callstack to determine which structure is currently being
    // parsed, but TUnion actually implements of read/write. So when the parser comes to any TUnion,
    // it only knows TUnion from the stack, but not the specific thrift struct.
    // So here we only test serialization, not the deserialization part.
    @Test
    public void tTextProtocolWriteUnionTest() throws Exception {
        TTextProtocolTestMsgUnion msg = new TTextProtocolTestMsgUnion();
        msg.setU(TestUnion.f2(2));

        // Serialize that thrift message with union out to a byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        msg.write(new TTextProtocol(new TIOStreamTransport(baos)));

        String  expectedMsg =
                "{\n" +
                "  \"u\" : {\n" +
                "    \"f2\" : 2\n" +
                "  }\n" +
                "}";

        assertEquals(expectedMsg, baos.toString());
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
                "}";

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

        assertEquals(request, new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
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
                "}";

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
                "}";

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

        assertEquals(request, new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
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
                "}";

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

        assertEquals(request, new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
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
                "}";

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

        assertEquals(request, new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
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
                "}";
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
                "}";
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
                "}";
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
                "}";
        TTextProtocol prot = new TTextProtocol(
                new TIOStreamTransport(new ByteArrayInputStream(request.getBytes())));
        prot.readMessageBegin();
    }
}
