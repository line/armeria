/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.RELAY_STATE;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.deserialize;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.serialize;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.sign;
import static java.util.Objects.requireNonNull;
import static net.shibboleth.utilities.java.support.xml.SerializeSupport.nodeToString;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.saml.common.messaging.context.SAMLBindingContext;
import org.opensaml.security.credential.Credential;

import com.google.common.collect.ImmutableList;
import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.saml.SamlService.SamlParameters;

/**
 * A utility class which supports HTTP POST binding protocol.
 */
final class HttpPostBindingUtil {

    private static final ImmutableList<String> XHTML = ImmutableList.of(
            // 0
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"DTD/xhtml1-strict.dtd\">" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\" lang=\"en\">" +
            "<head><meta http-equiv=\"content-type\" content=\"text/html;charset=utf-8\" /></head>" +
            "<body onload=\"document.forms[0].submit()\">",
            // 1, 2
            "<form method=\"post\" action=\"", "\">",
            // 3, 4, 5
            "<input type=\"hidden\" name=\"", "\" value=\"", "\" />",
            // 6
            "</form></body></html>");

    private static final Escaper HTML_ESCAPER = HtmlEscapers.htmlEscaper();

    /**
     * Returns an {@link HttpData} which holds a SSO form.
     */
    static HttpData getSsoForm(String remoteEndpointUrl,
                               String paramName, String paramValue,
                               @Nullable String relayState) {
        requireNonNull(remoteEndpointUrl, "remoteEndpointUrl");
        requireNonNull(paramName, "paramName");
        requireNonNull(paramValue, "paramValue");

        final StringBuilder html = new StringBuilder();
        html.append(XHTML.get(0))
            .append(XHTML.get(1)).append(HTML_ESCAPER.escape(remoteEndpointUrl)).append(XHTML.get(2))
            .append(XHTML.get(3)).append(HTML_ESCAPER.escape(paramName))
            .append(XHTML.get(4)).append(HTML_ESCAPER.escape(paramValue)).append(XHTML.get(5));

        if (relayState != null) {
            html.append(XHTML.get(3)).append(RELAY_STATE)
                .append(XHTML.get(4)).append(HTML_ESCAPER.escape(relayState)).append(XHTML.get(5));
        }
        html.append(XHTML.get(6));

        return HttpData.ofUtf8(html.toString());
    }

    /**
     * Signs the specified {@link SignableSAMLObject} with the specified {@link Credential} and
     * {@code signatureAlgorithm}, and then encodes the object into a base64 string.
     */
    static String toSignedBase64(SignableSAMLObject signableObj,
                                 Credential signingCredential,
                                 String signatureAlgorithm) {
        sign(signableObj, signingCredential, signatureAlgorithm);
        final String messageStr = nodeToString(serialize(signableObj));
        return Base64.getEncoder().encodeToString(messageStr.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Converts an {@link AggregatedHttpRequest} which is received from the remote entity to
     * a {@link SAMLObject}.
     */
    static <T extends SAMLObject> MessageContext<T> toSamlObject(AggregatedHttpRequest req, String name) {
        final SamlParameters parameters = new SamlParameters(req);
        final byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(parameters.getFirstValue(name));
        } catch (IllegalArgumentException e) {
            throw new InvalidSamlRequestException(
                    "failed to decode a base64 string of the parameter: " + name, e);
        }

        @SuppressWarnings("unchecked")
        final T message = (T) deserialize(decoded);

        final MessageContext<T> messageContext = new MessageContext<>();
        messageContext.setMessage(message);

        @Nullable
        final String relayState = parameters.getFirstValueOrNull(RELAY_STATE);
        if (relayState != null) {
            final SAMLBindingContext context = messageContext.getSubcontext(SAMLBindingContext.class, true);
            assert context != null;
            context.setRelayState(relayState);
        }

        return messageContext;
    }

    private HttpPostBindingUtil() {}
}
