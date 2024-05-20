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

import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SAML_RESPONSE;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.validateSignature;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.messaging.context.SAMLBindingContext;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.EncryptedAssertion;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.saml.saml2.encryption.Decrypter;
import org.opensaml.security.credential.Credential;
import org.opensaml.xmlsec.encryption.support.DecryptionException;
import org.opensaml.xmlsec.encryption.support.InlineEncryptedKeyResolver;
import org.opensaml.xmlsec.keyinfo.impl.StaticKeyInfoCredentialResolver;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A service which receives an assertion from the remote identity provider.
 */
final class SamlAssertionConsumerFunction implements SamlServiceFunction {

    private static final long MILLIS_IN_MINUTE = TimeUnit.MINUTES.toMillis(1);

    private final SamlAssertionConsumerConfig cfg;
    private final String entityId;
    private final Map<String, SamlIdentityProviderConfig> idpConfigs;
    @Nullable
    private final SamlIdentityProviderConfig defaultIdpConfig;

    private final SamlRequestIdManager requestIdManager;
    private final SamlSingleSignOnHandler ssoHandler;
    private final boolean signatureRequired;

    SamlAssertionConsumerFunction(SamlAssertionConsumerConfig cfg, String entityId,
                                  Map<String, SamlIdentityProviderConfig> idpConfigs,
                                  @Nullable SamlIdentityProviderConfig defaultIdpConfig,
                                  SamlRequestIdManager requestIdManager,
                                  SamlSingleSignOnHandler ssoHandler,
                                  boolean signatureRequired) {
        this.cfg = cfg;
        this.entityId = entityId;
        this.idpConfigs = idpConfigs;
        this.defaultIdpConfig = defaultIdpConfig;
        this.requestIdManager = requestIdManager;
        this.ssoHandler = ssoHandler;
        this.signatureRequired = signatureRequired;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, AggregatedHttpRequest req,
                              String defaultHostname, SamlPortConfig portConfig) {
        MessageContext<Response> messageContext = null;

        try {
            final SamlBindingProtocol bindingProtocol = cfg.endpoint().bindingProtocol();
            if (bindingProtocol == SamlBindingProtocol.HTTP_REDIRECT) {
                messageContext = HttpRedirectBindingUtil.toSamlObject(req, SAML_RESPONSE,
                                                                      idpConfigs, defaultIdpConfig,
                                                                      signatureRequired);
            } else {
                messageContext = HttpPostBindingUtil.toSamlObject(req, SAML_RESPONSE);
            }

            final String endpointUri = cfg.endpoint().toUriString(portConfig.scheme().uriText(),
                                                                  defaultHostname, portConfig.port());
            final Response response = messageContext.getMessage();
            assert response != null;

            final Assertion assertion = getValidatedAssertion(bindingProtocol, response, endpointUri);

            // Find a session index which is sent by an identity provider.
            final String sessionIndex = assertion.getAuthnStatements().stream()
                                                 .map(AuthnStatement::getSessionIndex)
                                                 .filter(Objects::nonNull)
                                                 .findFirst().orElse(null);

            final SAMLBindingContext bindingContext = messageContext.getSubcontext(SAMLBindingContext.class);
            final String relayState = bindingContext != null ? bindingContext.getRelayState() : null;

            return ssoHandler.loginSucceeded(ctx, req, messageContext, sessionIndex, relayState);
        } catch (SamlException e) {
            return ssoHandler.loginFailed(ctx, req, messageContext, e);
        }
    }

    private SamlIdentityProviderConfig resolveIdpConfig(Issuer issuer) {
        final String value = issuer.getValue();
        if (value != null) {
            final SamlIdentityProviderConfig config = idpConfigs.get(value);
            if (config != null) {
                return config;
            }
        }
        throw new InvalidSamlRequestException("failed to find identity provider from configuration: " +
                                              issuer.getValue());
    }

    private Assertion getValidatedAssertion(SamlBindingProtocol bindingProtocol,
                                            Response response, String endpointUri) {
        final Status status = response.getStatus();
        final String statusCode = status.getStatusCode().getValue();
        if (!StatusCode.SUCCESS.equals(statusCode)) {
            throw new InvalidSamlRequestException("response status code: " + statusCode +
                                                  " (expected: " + StatusCode.SUCCESS + ')');
        }

        final DateTime now = new DateTime();
        final DateTime issueInstant = response.getIssueInstant();
        if (issueInstant == null) {
            throw new InvalidSamlRequestException("failed to get IssueInstant attribute");
        }
        if (Math.abs(now.getMillis() - issueInstant.getMillis()) > MILLIS_IN_MINUTE) {
            // Allow if 'issueInstant' is in [now - 60s, now + 60s] because there might be the
            // time difference between SP's timer and IdP's timer.
            throw new InvalidSamlRequestException("invalid IssueInstant: " + issueInstant +
                                                  " (now: " + now + ')');
        }

        final List<Assertion> assertions;
        if (response.getEncryptedAssertions().isEmpty()) {
            assertions = response.getAssertions();
        } else {
            // - The <Issuer> element MAY be omitted, but if present it MUST contain the unique identifier
            //   of the issuing identity provider; the Format attribute MUST be omitted or have a value of
            //   urn:oasis:names:tc:SAML:2.0:nameid-format:entity.
            final SamlIdentityProviderConfig idp;
            final Issuer issuer = response.getIssuer();
            if (issuer != null) {
                idp = resolveIdpConfig(issuer);
            } else {
                // If assertions are encrypted, IdP's encryption credential is necessary to decrypt them.
                // A default IdP configuration will be used if there is no Issuer element in the response.
                if (defaultIdpConfig == null) {
                    throw new SamlException("failed to decrypt an assertion because there is no credential");
                }
                idp = defaultIdpConfig;
            }

            final ImmutableList.Builder<Assertion> builder = new ImmutableList.Builder<>();
            for (final EncryptedAssertion encryptedAssertion : response.getEncryptedAssertions()) {
                builder.add(decryptAssertion(encryptedAssertion, idp.encryptionCredential()));
            }
            builder.addAll(response.getAssertions());
            assertions = builder.build();
        }

        // - It MUST contain at least one <Assertion>. Each assertion's <Issuer> element MUST contain the
        //   unique identifier of the issuing identity provider; the Format attribute MUST be omitted or
        //   have a value of urn:oasis:names:tc:SAML:2.0:nameid-format:entity.
        if (assertions.isEmpty()) {
            throw new InvalidSamlRequestException("failed to get Assertion elements from the response");
        }

        // - The set of one or more assertions MUST contain at least one <AuthnStatement> that reflects the
        //   authentication of the principal to the identity provider.
        for (final Assertion assertion : assertions) {
            final Issuer issuer = assertion.getIssuer();
            if (issuer == null || issuer.getValue() == null) {
                throw new InvalidSamlRequestException("failed to get an Issuer element from the assertion");
            }

            final SamlIdentityProviderConfig idp = resolveIdpConfig(issuer);

            if (bindingProtocol != SamlBindingProtocol.HTTP_REDIRECT) {
                validateSignature(idp.signingCredential(), response, signatureRequired);
            } else {
                // The above `HttpRedirectBindingUtil.toSamlObject()` call performed the validation already.
            }

            validateSignature(idp.signingCredential(), assertion, signatureRequired);

            final List<AuthnStatement> authnStatements = assertion.getAuthnStatements();
            if (authnStatements.isEmpty()) {
                continue;
            }

            final Subject subject = assertion.getSubject();
            if (subject == null) {
                continue;
            }

            // - At least one assertion containing an <AuthnStatement> MUST contain a <Subject> element with
            //   at least one <SubjectConfirmation> element containing a Method of
            //   urn:oasis:names:tc:SAML:2.0:cm:bearer. If the identity provider supports the Single Logout
            //   idp, defined in Section 4.4, any such authentication statements MUST include a SessionIndex
            //   attribute to enable per-session logout requests by the service provider.
            //
            // - The bearer <SubjectConfirmation> element described above MUST contain a
            //   <SubjectConfirmationData> element that contains a Recipient attribute containing the service
            //   provider's assertion consumer service URL and a NotOnOrAfter attribute that limits the window
            //   during which the assertion can be delivered. It MAY contain an Address attribute limiting
            //   the client address from which the assertion can be delivered.
            //   It MUST NOT contain a NotBefore attribute. If the containing message is in response to
            //   an <AuthnRequest>, then the InResponseTo attribute MUST match the request's ID.
            final List<SubjectConfirmation> subjectConfirmations = subject.getSubjectConfirmations();
            for (final SubjectConfirmation subjectConfirmation : subjectConfirmations) {
                if (!"urn:oasis:names:tc:SAML:2.0:cm:bearer".equals(subjectConfirmation.getMethod())) {
                    continue;
                }
                final SubjectConfirmationData data = subjectConfirmation.getSubjectConfirmationData();
                if (data == null) {
                    continue;
                }

                if (!endpointUri.equals(data.getRecipient())) {
                    throw new InvalidSamlRequestException(
                            "recipient is not matched: " + data.getRecipient());
                }
                if (now.isAfter(data.getNotOnOrAfter())) {
                    throw new InvalidSamlRequestException(
                            "response has been expired: " + data.getNotOnOrAfter());
                }
                if (!requestIdManager.validateId(data.getInResponseTo())) {
                    throw new InvalidSamlRequestException(
                            "request ID is not valid: " + data.getInResponseTo());
                }

                final Conditions conditions = assertion.getConditions();
                if (conditions == null) {
                    throw new InvalidSamlRequestException("no condition found from the assertion");
                }

                // - The assertion(s) containing a bearer subject confirmation MUST contain an
                //   <AudienceRestriction> including the service provider's unique identifier as an <Audience>.
                //
                // - Other conditions (and other <Audience> elements) MAY be included as requested by the
                //   service provider or at the discretion of the identity provider. (Of course, all such
                //   conditions MUST be understood by and accepted by the service provider in order for
                //   the assertion to be considered valid.) The identity provider is NOT obligated to honor
                //   the requested set of <Conditions> in the <AuthnRequest>, if any.
                final Optional<Audience> audience =
                        conditions.getAudienceRestrictions().stream()
                                  .flatMap(r -> r.getAudiences().stream())
                                  .filter(audience0 -> entityId
                                          .equals(audience0.getAudienceURI()))
                                  .findAny();
                if (!audience.isPresent()) {
                    throw new InvalidSamlRequestException("no audience found from the assertion");
                }

                return assertion;
            }
        }
        throw new InvalidSamlRequestException("no subject found from the assertions");
    }

    private static Assertion decryptAssertion(EncryptedAssertion encryptedAssertion,
                                              Credential decryptionCredential) {
        final StaticKeyInfoCredentialResolver keyInfoCredentialResolver =
                new StaticKeyInfoCredentialResolver(decryptionCredential);
        final Decrypter decrypter =
                new Decrypter(null, keyInfoCredentialResolver, new InlineEncryptedKeyResolver());
        decrypter.setRootInNewDocument(true);
        try {
            return decrypter.decrypt(encryptedAssertion);
        } catch (DecryptionException e) {
            throw new InvalidSamlRequestException("failed to decrypt an assertion", e);
        }
    }
}
