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

import static com.linecorp.armeria.server.saml.SamlMessageUtil.build;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.builder;
import static net.shibboleth.utilities.java.support.xml.SerializeSupport.nodeToString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.opensaml.saml.common.SAMLObjectBuilder;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.metadata.AssertionConsumerService;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.KeyDescriptor;
import org.opensaml.saml.saml2.metadata.NameIDFormat;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml.saml2.metadata.SingleLogoutService;
import org.opensaml.security.SecurityException;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.UsageType;
import org.opensaml.xmlsec.keyinfo.KeyInfoGenerator;
import org.opensaml.xmlsec.keyinfo.impl.X509KeyInfoGeneratorFactory;
import org.opensaml.xmlsec.signature.KeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A service which returns the SAML metadata in response to a user request.
 */
final class SamlMetadataServiceFunction implements SamlServiceFunction {
    private static final Logger logger = LoggerFactory.getLogger(SamlMetadataServiceFunction.class);

    @VisibleForTesting
    static final MediaType CONTENT_TYPE_SAML_METADATA = MediaType.parse("application/samlmetadata+xml");

    private static final ResponseHeaders RESPONSE_HEADERS =
            ResponseHeaders.of(HttpStatus.OK,
                               HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_SAML_METADATA,
                               HttpHeaderNames.CONTENT_DISPOSITION,
                               "attachment; filename=\"saml_metadata.xml\"");

    private final String entityId;
    private final Credential signingCredential;
    private final Credential encryptionCredential;
    private final Map<String, SamlIdentityProviderConfig> idpConfigs;
    private final Collection<SamlAssertionConsumerConfig> assertionConsumerConfigs;
    private final Collection<SamlEndpoint> singleLogoutEndpoints;

    private final ConcurrentMap<String, HttpData> metadataMap = new MapMaker().makeMap();

    SamlMetadataServiceFunction(String entityId,
                                Credential signingCredential,
                                Credential encryptionCredential,
                                Map<String, SamlIdentityProviderConfig> idpConfigs,
                                Collection<SamlAssertionConsumerConfig> assertionConsumerConfigs,
                                Collection<SamlEndpoint> singleLogoutEndpoints) {
        this.entityId = entityId;
        this.signingCredential = signingCredential;
        this.encryptionCredential = encryptionCredential;
        this.idpConfigs = idpConfigs;
        this.assertionConsumerConfigs = assertionConsumerConfigs;
        this.singleLogoutEndpoints = singleLogoutEndpoints;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, AggregatedHttpRequest req,
                              String defaultHostname, SamlPortConfig portConfig) {
        final HttpData metadata = metadataMap.computeIfAbsent(defaultHostname, h -> {
            try {
                final Element element =
                        SamlMessageUtil.serialize(buildMetadataEntityDescriptorElement(h, portConfig));
                final HttpData newMetadata = HttpData.ofUtf8(nodeToString(element));
                logger.debug("SAML service provider metadata has been prepared for: {}.", h);
                return newMetadata;
            } catch (Throwable cause) {
                logger.warn("{} Unexpected metadata request.", ctx, cause);
                return HttpData.empty();
            }
        });

        if (metadata != HttpData.empty()) {
            return HttpResponse.of(RESPONSE_HEADERS, metadata);
        } else {
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        }
    }

    private EntityDescriptor buildMetadataEntityDescriptorElement(
            String defaultHostname, SamlPortConfig portConfig) {
        final EntityDescriptor entityDescriptor = build(EntityDescriptor.DEFAULT_ELEMENT_NAME);
        entityDescriptor.setEntityID(entityId);

        final SPSSODescriptor spSsoDescriptor = build(SPSSODescriptor.DEFAULT_ELEMENT_NAME);
        spSsoDescriptor.setAuthnRequestsSigned(true);
        spSsoDescriptor.setWantAssertionsSigned(true);
        spSsoDescriptor.addSupportedProtocol(SAMLConstants.SAML20P_NS);

        final List<String> nameIdFormats = idpConfigs.values().stream()
                                                     .map(p -> p.nameIdPolicy().format())
                                                     .distinct()
                                                     .map(SamlNameIdFormat::urn)
                                                     .collect(Collectors.toList());
        spSsoDescriptor.getNameIDFormats().addAll(buildNameIdFormatElements(nameIdFormats));

        final List<SingleLogoutService> sloList = spSsoDescriptor.getSingleLogoutServices();
        singleLogoutEndpoints.forEach(endpoint -> {
            final SingleLogoutService slo = build(SingleLogoutService.DEFAULT_ELEMENT_NAME);
            slo.setBinding(endpoint.bindingProtocol().urn());
            slo.setLocation(endpoint.toUriString(portConfig.scheme().uriText(),
                                                 defaultHostname,
                                                 portConfig.port()));
            sloList.add(slo);
        });

        int acsIndex = 0;
        final List<AssertionConsumerService> services = spSsoDescriptor.getAssertionConsumerServices();
        for (final SamlAssertionConsumerConfig acs : assertionConsumerConfigs) {
            services.add(buildAssertionConsumerServiceElement(acs, portConfig, defaultHostname, acsIndex++));
        }

        final X509KeyInfoGeneratorFactory keyInfoGeneratorFactory = new X509KeyInfoGeneratorFactory();
        keyInfoGeneratorFactory.setEmitEntityCertificate(true);
        keyInfoGeneratorFactory.setEmitEntityCertificateChain(true);
        final KeyInfoGenerator keyInfoGenerator = keyInfoGeneratorFactory.newInstance();

        try {
            spSsoDescriptor.getKeyDescriptors().add(
                    buildKeyDescriptorElement(UsageType.SIGNING,
                                              keyInfoGenerator.generate(signingCredential)));
            spSsoDescriptor.getKeyDescriptors().add(
                    buildKeyDescriptorElement(UsageType.ENCRYPTION,
                                              keyInfoGenerator.generate(encryptionCredential)));
        } catch (SecurityException e) {
            throw new SamlException("failed to generate KeyInfo element", e);
        }

        entityDescriptor.getRoleDescriptors().add(spSsoDescriptor);
        return entityDescriptor;
    }

    private static AssertionConsumerService buildAssertionConsumerServiceElement(
            SamlAssertionConsumerConfig config, SamlPortConfig portConfig, String hostname, int index) {
        final AssertionConsumerService consumer = build(AssertionConsumerService.DEFAULT_ELEMENT_NAME);

        consumer.setLocation(config.endpoint().toUriString(portConfig.scheme().uriText(),
                                                           hostname,
                                                           portConfig.port()));
        consumer.setBinding(config.endpoint().bindingProtocol().urn());
        consumer.setIndex(index);

        // Add 'isDefault' attribute only when told so.
        if (config.isDefault()) {
            consumer.setIsDefault(true);
        }
        return consumer;
    }

    private static Collection<NameIDFormat> buildNameIdFormatElements(Collection<String> nameIds) {
        final SAMLObjectBuilder<NameIDFormat> builder = builder(NameIDFormat.DEFAULT_ELEMENT_NAME);
        final Collection<NameIDFormat> formats = new ArrayList<>();
        for (final String value : nameIds) {
            final NameIDFormat nameIdFormat = builder.buildObject();
            nameIdFormat.setFormat(value);
            formats.add(nameIdFormat);
        }
        return formats;
    }

    private static KeyDescriptor buildKeyDescriptorElement(UsageType type, @Nullable KeyInfo key) {
        final KeyDescriptor descriptor = build(KeyDescriptor.DEFAULT_ELEMENT_NAME);
        descriptor.setUse(type);
        descriptor.setKeyInfo(key);
        return descriptor;
    }
}
