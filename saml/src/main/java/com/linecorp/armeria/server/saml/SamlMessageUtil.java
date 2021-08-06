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

import static java.util.Objects.requireNonNull;
import static org.opensaml.xmlsec.signature.support.SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import javax.xml.namespace.QName;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.Marshaller;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.core.xml.io.UnmarshallingException;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.SAMLObjectBuilder;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator;
import org.opensaml.security.SecurityException;
import org.opensaml.security.credential.Credential;
import org.opensaml.xmlsec.keyinfo.KeyInfoGenerator;
import org.opensaml.xmlsec.keyinfo.impl.X509KeyInfoGeneratorFactory;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.impl.SignatureBuilder;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.SignatureValidator;
import org.opensaml.xmlsec.signature.support.Signer;
import org.w3c.dom.Element;

import net.shibboleth.utilities.java.support.xml.ParserPool;
import net.shibboleth.utilities.java.support.xml.XMLParserException;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A utility class for SAML messages.
 */
final class SamlMessageUtil {

    private static final XMLObjectBuilderFactory builderFactory;

    private static final KeyInfoGenerator keyInfoGenerator;

    private static final SignatureBuilder signatureBuilder = new SignatureBuilder();

    private static final SAMLSignatureProfileValidator signatureProfileValidator =
            new SAMLSignatureProfileValidator();

    static {
        SamlInitializer.ensureAvailability();

        builderFactory = XMLObjectProviderRegistrySupport.getBuilderFactory();

        final X509KeyInfoGeneratorFactory keyInfoGeneratorFactory = new X509KeyInfoGeneratorFactory();
        keyInfoGeneratorFactory.setEmitEntityCertificate(true);
        keyInfoGeneratorFactory.setEmitEntityCertificateChain(true);
        keyInfoGenerator = keyInfoGeneratorFactory.newInstance();
    }

    @SuppressWarnings("unchecked")
    static <T extends SAMLObject> SAMLObjectBuilder<T> builder(@Nullable final QName key) {
        final SAMLObjectBuilder<T> builder = (SAMLObjectBuilder<T>) builderFactory.getBuilder(key);
        assert builder != null;
        return builder;
    }

    @SuppressWarnings("unchecked")
    static <T extends SAMLObject> T build(@Nullable final QName key) {
        return (T) builder(key).buildObject();
    }

    static Element serialize(XMLObject message) {
        requireNonNull(message, "message");

        if (message.getDOM() != null) {
            // Return cached DOM if it exists.
            return message.getDOM();
        }

        final Marshaller marshaller =
                XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(message);
        if (marshaller == null) {
            throw new SamlException("failed to serialize a SAML object into an XML document, " +
                                    "no serializer registered for message object: " +
                                    message.getElementQName());
        }

        try {
            return marshaller.marshall(message);
        } catch (MarshallingException e) {
            throw new SamlException("failed to serialize a SAML object into an XML document", e);
        }
    }

    static XMLObject deserialize(byte[] bytes) {
        requireNonNull(bytes, "bytes");
        final ParserPool parserPool = XMLObjectProviderRegistrySupport.getParserPool();
        assert parserPool != null;

        final InputStream is = new ByteArrayInputStream(bytes);
        try {
            return XMLObjectSupport.unmarshallFromInputStream(parserPool, is);
        } catch (XMLParserException | UnmarshallingException e) {
            throw new InvalidSamlRequestException(
                    "failed to deserialize an XML document bytes into a SAML object", e);
        }
    }

    /**
     * Signs the specified {@link SignableSAMLObject} with the specified {@link Credential} and
     * {@code signatureAlgorithm}.
     */
    static void sign(SignableSAMLObject signableObj, Credential signingCredential, String signatureAlgorithm) {
        requireNonNull(signableObj, "signableObj");
        requireNonNull(signingCredential, "signingCredential");
        requireNonNull(signatureAlgorithm, "signatureAlgorithm");

        final Signature signature = signatureBuilder.buildObject();
        signature.setSignatureAlgorithm(signatureAlgorithm);
        signature.setSigningCredential(signingCredential);
        signature.setCanonicalizationAlgorithm(ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        try {
            signature.setKeyInfo(keyInfoGenerator.generate(signingCredential));
        } catch (SecurityException e) {
            throw new SamlException("failed to create a key info of signing credential", e);
        }

        signableObj.setSignature(signature);
        serialize(signableObj);

        try {
            Signer.signObject(signature);
        } catch (SignatureException e) {
            throw new SamlException("failed to sign a SAML object", e);
        }
    }

    static void validateSignature(Credential validationCredential, SignableSAMLObject signableObj) {
        requireNonNull(validationCredential, "validationCredential");
        requireNonNull(signableObj, "signableObj");

        // Skip signature validation if the object is not signed.
        if (!signableObj.isSigned()) {
            return;
        }

        final Signature signature = signableObj.getSignature();
        if (signature == null) {
            throw new InvalidSamlRequestException("failed to validate a signature because no signature exists");
        }

        try {
            signatureProfileValidator.validate(signature);
            SignatureValidator.validate(signature, validationCredential);
        } catch (SignatureException e) {
            throw new InvalidSamlRequestException("failed to validate a signature", e);
        }
    }

    private SamlMessageUtil() {}
}
