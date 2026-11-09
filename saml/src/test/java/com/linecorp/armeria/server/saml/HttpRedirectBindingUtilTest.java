/*
 * Copyright 2026 LY Corporation
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
package com.linecorp.armeria.server.saml;

import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.DEFAULT_MAX_INFLATED_MESSAGE_LENGTH;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.fromDeflatedBase64;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.toDeflatedBase64;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.build;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.serialize;
import static net.shibboleth.utilities.java.support.xml.SerializeSupport.nodeToString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.NameID;

class HttpRedirectBindingUtilTest {

    @Test
    void roundTripPreservesTheMessage() {
        final LogoutRequest logoutRequest = newLogoutRequest();

        final XMLObject decoded =
                fromDeflatedBase64(toDeflatedBase64(logoutRequest), DEFAULT_MAX_INFLATED_MESSAGE_LENGTH);

        assertThat(decoded).isInstanceOf(LogoutRequest.class);
        final LogoutRequest actual = (LogoutRequest) decoded;
        assertThat(actual.getID()).isEqualTo(logoutRequest.getID());
        assertThat(actual.getIssuer().getValue()).isEqualTo("http://idp.example.com/redirect");
        assertThat(actual.getNameID().getFormat()).isEqualTo(SamlNameIdFormat.EMAIL.urn());
    }

    @Test
    void rejectsDecompressionBombBeforeExhaustingHeap() {
        // A ~64 MiB payload of zeros compresses to a few KiB but would inflate far past the ceiling.
        // The request must be rejected without ever materializing the full 64 MiB in the heap.
        final String bomb = deflateToBase64(new byte[64 * 1024 * 1024]);

        assertThatThrownBy(() -> fromDeflatedBase64(bomb, DEFAULT_MAX_INFLATED_MESSAGE_LENGTH))
                .isInstanceOf(InvalidSamlRequestException.class)
                .hasMessageContaining("exceeds the maximum allowed length");
    }

    @Test
    void acceptsInflatedOutputExactlyAtTheCeiling() {
        final LogoutRequest logoutRequest = newLogoutRequest();
        final byte[] serialized = nodeToString(serialize(logoutRequest)).getBytes(StandardCharsets.UTF_8);

        final LogoutRequest decoded = (LogoutRequest) fromDeflatedBase64(deflateToBase64(serialized),
                                                                       serialized.length);
        assertThat(decoded.getID()).isEqualTo(logoutRequest.getID());
    }

    @Test
    void rejectsInflatedOutputOneByteOverTheCeiling() {
        final String overCeiling = deflateToBase64(new byte[DEFAULT_MAX_INFLATED_MESSAGE_LENGTH + 1]);

        assertThatThrownBy(() -> fromDeflatedBase64(overCeiling, DEFAULT_MAX_INFLATED_MESSAGE_LENGTH))
                .isInstanceOf(InvalidSamlRequestException.class)
                .hasMessageContaining("exceeds the maximum allowed length");
    }

    @Test
    void honorsACustomMaxInflatedMessageLength() {
        final int customLimit = 4096;
        // One byte over the custom ceiling but far under the default one.
        final String payload = deflateToBase64(new byte[customLimit + 1]);

        // Rejected by the size check under the custom (smaller) ceiling.
        assertThatThrownBy(() -> fromDeflatedBase64(payload, customLimit))
                .isInstanceOf(InvalidSamlRequestException.class)
                .hasMessageContaining("exceeds the maximum allowed length");

        // The same payload passes the size check under the default, larger ceiling (then fails to
        // deserialize because raw zeros are not valid XML).
        assertThatThrownBy(() -> fromDeflatedBase64(payload, DEFAULT_MAX_INFLATED_MESSAGE_LENGTH))
                .isInstanceOf(InvalidSamlRequestException.class)
                .hasMessageNotContaining("exceeds the maximum allowed length");
    }

    @Test
    void rejectsInvalidBase64() {
        assertThatThrownBy(() -> fromDeflatedBase64("!!!not-base64!!!", DEFAULT_MAX_INFLATED_MESSAGE_LENGTH))
                .isInstanceOf(InvalidSamlRequestException.class)
                .hasMessageContaining("failed to decode a deflated base64 string");
    }

    @Test
    void rejectsMalformedDeflateStream() {
        // Valid base64, but the bytes are not a valid raw-DEFLATE stream (reserved block type).
        final String garbage = Base64.getEncoder().encodeToString(
                new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff });

        assertThatThrownBy(() -> fromDeflatedBase64(garbage, DEFAULT_MAX_INFLATED_MESSAGE_LENGTH))
                .isInstanceOf(InvalidSamlRequestException.class)
                .hasMessageContaining("failed to inflate a SAML message");
    }

    @Test
    void rejectsTruncatedDeflateStream() {
        final byte[] serialized = nodeToString(serialize(newLogoutRequest())).getBytes(StandardCharsets.UTF_8);
        // A non-final stored block contains a complete XML message but has no final block.
        final byte[] truncated = ByteBuffer.allocate(5 + serialized.length)
                                           .order(ByteOrder.LITTLE_ENDIAN)
                                           .put((byte) 0)
                                           .putShort((short) serialized.length)
                                           .putShort((short) ~serialized.length)
                                           .put(serialized)
                                           .array();

        assertThatThrownBy(() -> fromDeflatedBase64(Base64.getEncoder().encodeToString(truncated),
                                                   DEFAULT_MAX_INFLATED_MESSAGE_LENGTH))
                .isInstanceOf(InvalidSamlRequestException.class)
                .hasMessageContaining("truncated SAML message")
                .hasCauseInstanceOf(EOFException.class);
    }

    private static LogoutRequest newLogoutRequest() {
        final LogoutRequest logoutRequest = build(LogoutRequest.DEFAULT_ELEMENT_NAME);
        logoutRequest.setID("test-logout-id");
        logoutRequest.setIssueInstant(DateTime.now());

        final Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue("http://idp.example.com/redirect");
        logoutRequest.setIssuer(issuer);

        final NameID nameID = build(NameID.DEFAULT_ELEMENT_NAME);
        nameID.setFormat(SamlNameIdFormat.EMAIL.urn());
        logoutRequest.setNameID(nameID);

        return logoutRequest;
    }

    /**
     * Compresses {@code raw} with raw DEFLATE (nowrap, matching {@code new Inflater(true)}) and returns the
     * base64-encoded result, mirroring how an HTTP-Redirect binding message reaches the server.
     */
    private static String deflateToBase64(byte[] raw) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(raw);
        deflater.finish();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            final int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
