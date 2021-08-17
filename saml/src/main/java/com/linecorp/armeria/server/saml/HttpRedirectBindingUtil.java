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
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SIGNATURE;
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SIGNATURE_ALGORITHM;
import static java.util.Objects.requireNonNull;
import static net.shibboleth.utilities.java.support.xml.SerializeSupport.nodeToString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.messaging.context.SAMLBindingContext;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.RequestAbstractType;
import org.opensaml.saml.saml2.core.StatusResponseType;
import org.opensaml.security.SecurityException;
import org.opensaml.security.credential.Credential;
import org.opensaml.xmlsec.crypto.XMLSigningUtil;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.saml.SamlService.SamlParameters;

import io.netty.handler.codec.http.HttpHeaderValues;

/**
 * A utility class which supports HTTP-Redirect binding protocol.
 */
final class HttpRedirectBindingUtil {

    private static final String DEFAULT_CACHE_CONTROL =
            String.join(",", HttpHeaderValues.NO_CACHE, HttpHeaderValues.NO_STORE);
    private static final String DEFAULT_PRAGMA = HttpHeaderValues.NO_CACHE.toString();

    /**
     * Returns a {@link ResponseHeaders} with the specified {@code location}, the default {@code cache-control}
     * and the default {@code pragma} headers.
     */
    static ResponseHeaders headersWithLocation(String location) {
        return ResponseHeaders.of(HttpStatus.FOUND,
                                  HttpHeaderNames.LOCATION, location,
                                  HttpHeaderNames.CACHE_CONTROL, DEFAULT_CACHE_CONTROL,
                                  HttpHeaderNames.PRAGMA, DEFAULT_PRAGMA);
    }

    /**
     * Returns an {@link HttpResponse} with the specified {@code location}, the default {@code cache-control}
     * and the default {@code pragma} headers.
     */
    static HttpResponse responseWithLocation(String location) {
        return HttpResponse.of(headersWithLocation(location));
    }

    /**
     * Returns a redirected URL which includes a deflated base64 string that is converted from the specified
     * {@link SAMLObject}. The URL must contain a signature of the generated query string.
     */
    static String toRedirectionUrl(SAMLObject msg,
                                   String endpointUrl,
                                   String messageParamName,
                                   Credential signingCredential,
                                   String signatureAlgorithm,
                                   @Nullable String relayState) {
        requireNonNull(msg, "msg");
        requireNonNull(endpointUrl, "endpointUrl");
        requireNonNull(messageParamName, "messageParamName");
        requireNonNull(signingCredential, "signingCredential");
        requireNonNull(signatureAlgorithm, "signatureAlgorithm");

        final QueryParamsBuilder params = QueryParams.builder();
        params.add(messageParamName, toDeflatedBase64(msg));

        if (relayState != null) {
            // RelayState data MAY be included with a SAML protocol message transmitted with this binding.
            // The value MUST NOT exceed 80 bytes in length and SHOULD be integrity protected by the entity
            // creating the message independent of any other protections that may or may not exist
            // during message transmission.
            if (relayState.length() > 80) {
                throw new IllegalArgumentException("too long relayState string: " + relayState.length());
            }
            params.add(RELAY_STATE, relayState);
        }

        params.add(SIGNATURE_ALGORITHM, signatureAlgorithm);

        // Use URL-encoded query string as input.
        final String input = params.toQueryString();
        final String output = generateSignature(signingCredential, signatureAlgorithm, input);
        params.add(SIGNATURE, output);

        return endpointUrl + '?' + params.toQueryString();
    }

    /**
     * Validates a signature in the specified {@link AggregatedHttpRequest}.
     */
    private static void validateSignature(Credential validationCredential,
                                          SamlParameters parameters,
                                          String messageParamName) {
        requireNonNull(validationCredential, "validationCredential");
        requireNonNull(parameters, "parameters");
        requireNonNull(messageParamName, "messageParamName");

        final String signature = parameters.getFirstValue(SIGNATURE);
        final String sigAlg = parameters.getFirstValue(SIGNATURE_ALGORITHM);

        // The order is one of the followings:
        // - SAMLRequest={value}&RelayState={value}=SigAlg={value}
        // - SAMLResponse={value}&RelayState={value}=SigAlg={value}
        final QueryParamsBuilder params = QueryParams.builder();
        params.add(messageParamName, parameters.getFirstValue(messageParamName));

        final String relayState = parameters.getFirstValueOrNull(RELAY_STATE);
        if (relayState != null) {
            params.add(RELAY_STATE, relayState);
        }
        params.add(SIGNATURE_ALGORITHM, sigAlg);

        final byte[] input = params.toQueryString().getBytes(StandardCharsets.UTF_8);

        try {
            final byte[] decodedSignature = Base64.getMimeDecoder().decode(signature);
            if (!XMLSigningUtil.verifyWithURI(validationCredential, sigAlg, decodedSignature, input)) {
                throw new InvalidSamlRequestException("failed to validate a signature");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidSamlRequestException("failed to decode a base64 signature string", e);
        } catch (SecurityException e) {
            throw new InvalidSamlRequestException("failed to validate a signature", e);
        }
    }

    /**
     * Generates a signature of the specified {@code input}.
     */
    @VisibleForTesting
    static String generateSignature(Credential signingCredential, String algorithmURI, String input) {
        try {
            final byte[] signature =
                    XMLSigningUtil.signWithURI(signingCredential, algorithmURI,
                                               input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (SecurityException e) {
            throw new SamlException("failed to generate a signature", e);
        }
    }

    /**
     * Encodes the specified {@code message} into a deflated base64 string.
     */
    static String toDeflatedBase64(SAMLObject message) {
        requireNonNull(message, "message");

        final String messageStr;
        try {
            messageStr = nodeToString(XMLObjectSupport.marshall(message));
        } catch (MarshallingException e) {
            throw new SamlException("failed to serialize a SAML message", e);
        }

        final ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflaterStream =
                     new DeflaterOutputStream(Base64.getEncoder().wrap(bytesOut),
                                              new Deflater(Deflater.DEFLATED, true))) {
            deflaterStream.write(messageStr.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SamlException("failed to deflate a SAML message", e);
        }
        return bytesOut.toString();
    }

    /**
     * Decodes, inflates and deserializes the specified base64-encoded message to an {@link XMLObject}.
     */
    static XMLObject fromDeflatedBase64(String base64Encoded) {
        requireNonNull(base64Encoded, "base64Encoded");

        final byte[] base64decoded;
        try {
            base64decoded = Base64.getMimeDecoder().decode(base64Encoded);
        } catch (IllegalArgumentException e) {
            throw new InvalidSamlRequestException("failed to decode a deflated base64 string", e);
        }

        final ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        try (InflaterOutputStream inflaterOutputStream =
                     new InflaterOutputStream(bytesOut, new Inflater(true))) {
            inflaterOutputStream.write(base64decoded);
        } catch (IOException e) {
            throw new InvalidSamlRequestException("failed to inflate a SAML message", e);
        }

        return SamlMessageUtil.deserialize(bytesOut.toByteArray());
    }

    /**
     * Converts an {@link AggregatedHttpRequest} which is received from the remote entity to
     * a {@link SAMLObject}.
     */
    @SuppressWarnings("unchecked")
    static <T extends SAMLObject> MessageContext<T> toSamlObject(
            AggregatedHttpRequest req, String name,
            Map<String, SamlIdentityProviderConfig> idpConfigs,
            @Nullable SamlIdentityProviderConfig defaultIdpConfig) {
        requireNonNull(req, "req");
        requireNonNull(name, "name");
        requireNonNull(idpConfigs, "idpConfigs");

        final SamlParameters parameters = new SamlParameters(req);
        final T message = (T) fromDeflatedBase64(parameters.getFirstValue(name));

        final MessageContext<T> messageContext = new MessageContext<>();
        messageContext.setMessage(message);

        final Issuer issuer;
        if (message instanceof RequestAbstractType) {
            issuer = ((RequestAbstractType) message).getIssuer();
        } else if (message instanceof StatusResponseType) {
            issuer = ((StatusResponseType) message).getIssuer();
        } else {
            throw new InvalidSamlRequestException(
                    "invalid message type: " + message.getClass().getSimpleName());
        }

        // Use the default identity provider config if there's no issuer.
        final SamlIdentityProviderConfig config;
        if (issuer != null) {
            final String idpEntityId = issuer.getValue();
            config = idpConfigs.get(idpEntityId);
            if (config == null) {
                throw new InvalidSamlRequestException(
                        "a message from unknown identity provider: " + idpEntityId);
            }
        } else {
            if (defaultIdpConfig == null) {
                throw new InvalidSamlRequestException("failed to get an Issuer element");
            }
            config = defaultIdpConfig;
        }

        // If this message is sent via HTTP-redirect binding protocol, its signature parameter should
        // be validated.
        validateSignature(config.signingCredential(), parameters, name);

        final String relayState = parameters.getFirstValueOrNull(RELAY_STATE);
        if (relayState != null) {
            final SAMLBindingContext context = messageContext.getSubcontext(SAMLBindingContext.class, true);
            assert context != null;
            context.setRelayState(relayState);
        }

        return messageContext;
    }

    private HttpRedirectBindingUtil() {}
}
