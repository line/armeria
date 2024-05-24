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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.server.saml.HttpPostBindingUtil.toSignedBase64;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.generateSignature;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.headersWithLocation;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.toDeflatedBase64;
import static com.linecorp.armeria.server.saml.SamlEndpoint.ofHttpPost;
import static com.linecorp.armeria.server.saml.SamlEndpoint.ofHttpRedirect;
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.RELAY_STATE;
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SAML_REQUEST;
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SAML_RESPONSE;
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SIGNATURE;
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SIGNATURE_ALGORITHM;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.build;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.deserialize;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.serialize;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.sign;
import static com.linecorp.armeria.server.saml.SamlMetadataServiceFunction.CONTENT_TYPE_SAML_METADATA;
import static java.util.Objects.requireNonNull;
import static net.shibboleth.utilities.java.support.xml.SerializeSupport.nodeToString;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.opensaml.core.criterion.EntityIdCriterion;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.saml.common.messaging.context.SAMLBindingContext;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.saml.saml2.metadata.AssertionConsumerService;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.KeyDescriptor;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml.saml2.metadata.SingleLogoutService;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.CredentialResolver;
import org.opensaml.security.credential.impl.KeyStoreCredentialResolver;
import org.opensaml.xmlsec.signature.support.SignatureConstants;

import net.shibboleth.utilities.java.support.resolver.CriteriaSet;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@GenerateNativeImageTrace
class SamlServiceProviderTest {

    private static final AtomicReference<MessageContext<Response>> messageContextHolder =
            new AtomicReference<>();

    private static final String signatureAlgorithm = SignatureConstants.ALGO_ID_SIGNATURE_RSA;

    private static final String spHostname = "localhost";
    // Entity ID can be any form of a string. A URI string is one of the general forms of that.
    private static final String spEntityId = "http://127.0.0.1";
    private static final CredentialResolver spCredentialResolver;

    private static final Credential idpCredential;

    private static final Credential badIdpCredential;

    private static final SamlRequestIdManager requestIdManager = new SequentialRequestIdManager();

    static {
        try {
            // Create IdP's key store for testing.
            final SelfSignedCertificate idp = new SelfSignedCertificate();
            idpCredential = toIdpCredential(idp);

            // Create a bad IdP's key store to ensure our SAML implementation rejects an invalid signature.
            badIdpCredential = toIdpCredential(new SelfSignedCertificate());

            // Create my key store for testing.
            final KeyStore myKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            myKeyStore.load(null, null);

            final SelfSignedCertificate mine = new SelfSignedCertificate();
            // Add my keys for signing and encryption.
            myKeyStore.setKeyEntry("signing", mine.key(), "".toCharArray(),
                                   new Certificate[] { mine.cert() });
            myKeyStore.setKeyEntry("encryption", mine.key(), "".toCharArray(),
                                   new Certificate[] { mine.cert() });

            // Add IdPs' certificates for validating a SAML message from the IdP.
            // By default, Armeria finds the certificate whose name equals to the entity ID of an IdP,
            // so we are adding the certificate with IdP's entity ID.
            myKeyStore.setCertificateEntry("http://idp.example.com/post", idp.cert());
            myKeyStore.setCertificateEntry("http://idp.example.com/redirect", idp.cert());

            // Create a password map for my keys.
            final Map<String, String> myKeyPasswords = ImmutableMap.of("signing", "",
                                                                       "encryption", "");
            spCredentialResolver = new KeyStoreCredentialResolver(myKeyStore, myKeyPasswords);
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    private static Credential toIdpCredential(SelfSignedCertificate ssc) throws Exception {
        final KeyStore idpKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        idpKeyStore.load(null, null);

        idpKeyStore.setKeyEntry("signing", ssc.key(), "".toCharArray(), new Certificate[] { ssc.cert() });
        final CredentialResolver idpCredentialResolver =
                new KeyStoreCredentialResolver(idpKeyStore, ImmutableMap.of("signing", ""));

        final CriteriaSet badCs = new CriteriaSet();
        badCs.add(new EntityIdCriterion("signing"));

        final Credential credential = idpCredentialResolver.resolveSingle(badCs);
        assert credential != null;
        return credential;
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final SamlIdentityProviderConfigSelector configSelector =
                    (configurator, ctx, req) -> {
                        final String idpEntityId = "http://idp.example.com/" + ctx.pathParam("bindingProtocol");
                        return UnmodifiableFuture.completedFuture(configurator.idpConfigs().get(idpEntityId));
                    };
            final SamlServiceProvider sp =
                    SamlServiceProvider.builder()
                                       // A request will be authenticated if it contains 'test=test'
                                       // cookie in Cookie header.
                                       .authorizer(new CookieBasedAuthorizer("test", "test"))
                                       .ssoHandler(new CookieBasedSsoHandler("test", "test"))
                                       // My entity ID
                                       .entityId(spEntityId)
                                       .hostname(spHostname)
                                       //.scheme(SessionProtocol.HTTP)
                                       .credentialResolver(spCredentialResolver)
                                       .signatureAlgorithm(signatureAlgorithm)
                                       // Add a dummy IdP which supports HTTP-Post binding protocol for SSO.
                                       .idp()
                                       .entityId("http://idp.example.com/post")
                                       .ssoEndpoint(ofHttpPost("http://idp.example.com/saml/sso/post"))
                                       .sloResEndpoint(ofHttpPost("http://idp.example.com/saml/slo/post"))
                                       .and()
                                       // Add one more dummy IdP which supports
                                       // HTTP-Redirect binding protocol for SSO.
                                       .idp()
                                       .entityId("http://idp.example.com/redirect")
                                       .ssoEndpoint(ofHttpRedirect("http://idp.example.com/saml/sso/redirect"))
                                       .sloResEndpoint(ofHttpRedirect("http://idp.example.com/saml/slo/redirect"))
                                       .and()
                                       // We have two IdP config so one of them
                                       // will be selected by the path variable.
                                       .idpConfigSelector(configSelector)
                                       .requestIdManager(requestIdManager)
                                       .build();

            sb.service(sp.newSamlService())
              .annotatedService("/", new Object() {
                  @Get("/{bindingProtocol}")
                  public String root() {
                      return "authenticated";
                  }
              }, sp.newSamlDecorator());
        }
    };

    static class CookieBasedAuthorizer implements Authorizer<HttpRequest> {
        private final String cookieName;
        private final String cookieValue;

        CookieBasedAuthorizer(String cookieName, String cookieValue) {
            this.cookieName = requireNonNull(cookieName, "cookieName");
            this.cookieValue = requireNonNull(cookieValue, "cookieValue");
        }

        @Override
        public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req) {
            final String value = req.headers().get(HttpHeaderNames.COOKIE);
            if (value == null) {
                return UnmodifiableFuture.completedFuture(false);
            }

            // Authentication will be succeeded only if both the specified cookie name and value are matched.
            final Set<Cookie> cookies = Cookie.fromCookieHeader(value);
            final boolean result = cookies.stream().anyMatch(
                    cookie -> cookieName.equals(cookie.name()) && cookieValue.equals(cookie.value()));
            return UnmodifiableFuture.completedFuture(result);
        }
    }

    static class CookieBasedSsoHandler implements SamlSingleSignOnHandler {
        private final String setCookie;

        CookieBasedSsoHandler(String cookieName, String cookieValue) {
            requireNonNull(cookieName, "cookieName");
            requireNonNull(cookieValue, "cookieValue");

            final Cookie cookie = Cookie.secureBuilder(cookieName, cookieValue)
                                        .domain(spHostname)
                                        .path("/")
                                        .build();
            setCookie = cookie.toSetCookieHeader();
        }

        @Override
        public CompletionStage<Void> beforeInitiatingSso(ServiceRequestContext ctx, HttpRequest req,
                                                         MessageContext<AuthnRequest> message,
                                                         SamlIdentityProviderConfig idpConfig) {
            message.getSubcontext(SAMLBindingContext.class, true)
                   .setRelayState(req.path());
            return UnmodifiableFuture.completedFuture(null);
        }

        @Override
        public HttpResponse loginSucceeded(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                           MessageContext<Response> message, @Nullable String sessionIndex,
                                           @Nullable String relayState) {
            return HttpResponse.of(headersWithLocation(firstNonNull(relayState, "/"))
                                           .toBuilder()
                                           .add(HttpHeaderNames.SET_COOKIE, setCookie)
                                           .build());
        }

        @Override
        public HttpResponse loginFailed(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                        @Nullable MessageContext<Response> message, Throwable cause) {
            messageContextHolder.set(message);
            // Handle as an error so that a test client can detect the failure.
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }
    }

    static class SequentialRequestIdManager implements SamlRequestIdManager {
        private final AtomicInteger id = new AtomicInteger();

        @Override
        public String newId() {
            return String.valueOf(id.getAndIncrement());
        }

        @Override
        public boolean validateId(String id) {
            try {
                Integer.parseInt(id);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    final WebClient client = WebClient.of(server.httpUri());

    @BeforeEach
    void setup() {
        messageContextHolder.set(null);
    }

    @Test
    void shouldRespondAuthnRequest_HttpRedirect() throws Exception {
        final AggregatedHttpResponse resp = client.get("/redirect").aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.FOUND);

        // Check the order of the parameters in the quest string.
        final String location = resp.headers().get(HttpHeaderNames.LOCATION);
        final Pattern p = Pattern.compile(
                "http://idp\\.example\\.com/saml/sso/redirect\\?" +
                "SAMLRequest=([^&]+)&RelayState=([^&]+)&SigAlg=([^&]+)&Signature=(.+)$");
        assertThat(location).isNotNull();
        assertThat(p.matcher(location).matches()).isTrue();

        assertThat(QueryParams.fromQueryString(location)
                              .get(SIGNATURE_ALGORITHM)).isEqualTo(signatureAlgorithm);
    }

    @Test
    void shouldRespondAuthnRequest_HttpPost() throws Exception {
        final AggregatedHttpResponse resp = client.get("/post").aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        assertThat(resp.contentType()).isEqualTo(MediaType.HTML_UTF_8);

        final Document doc = Jsoup.parse(resp.contentUtf8());
        assertThat(doc.body().attr("onLoad")).isEqualTo("document.forms[0].submit()");

        // SAMLRequest will be posted to the IdP's SSO URL.
        final Element form = doc.body().child(0);
        assertThat(form.attr("method")).isEqualTo("post");
        assertThat(form.attr("action")).isEqualTo("http://idp.example.com/saml/sso/post");
        assertThat(form.child(0).attr("name")).isEqualTo(SAML_REQUEST);
        assertThat(form.child(1).attr("name")).isEqualTo(RELAY_STATE);
    }

    @Test
    void shouldBeAlreadyAuthenticated() throws Exception {
        final RequestHeaders req = RequestHeaders.of(HttpMethod.GET, "/redirect",
                                                     HttpHeaderNames.COOKIE, "test=test");
        final AggregatedHttpResponse resp = client.execute(req).aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        assertThat(resp.contentUtf8()).isEqualTo("authenticated");
    }

    @Test
    void shouldRespondMetadataWithoutAuthentication() throws Exception {
        final AggregatedHttpResponse resp = client.get("/saml/metadata").aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        assertThat(resp.contentType()).isEqualTo(CONTENT_TYPE_SAML_METADATA);

        final EntityDescriptor metadata =
                (EntityDescriptor) deserialize(resp.contentUtf8().getBytes());
        assertThat(metadata).isNotNull();

        final SPSSODescriptor sp = metadata.getSPSSODescriptor(SAMLConstants.SAML20P_NS);
        assertThat(sp.isAuthnRequestsSigned()).isTrue();
        assertThat(sp.getWantAssertionsSigned()).isTrue();

        final List<KeyDescriptor> kd = sp.getKeyDescriptors();
        assertThat(kd.get(0).getUse().name()).isEqualToIgnoringCase("signing");
        assertThat(kd.get(1).getUse().name()).isEqualToIgnoringCase("encryption");

        final List<SingleLogoutService> slo = sp.getSingleLogoutServices();
        assertThat(slo.get(0).getLocation())
                .isEqualTo("http://" + spHostname + ':' + server.httpPort() + "/saml/slo/post");
        assertThat(slo.get(0).getBinding()).isEqualTo(SAMLConstants.SAML2_POST_BINDING_URI);
        assertThat(slo.get(1).getLocation())
                .isEqualTo("http://" + spHostname + ':' + server.httpPort() + "/saml/slo/redirect");
        assertThat(slo.get(1).getBinding()).isEqualTo(SAMLConstants.SAML2_REDIRECT_BINDING_URI);

        final List<AssertionConsumerService> acs = sp.getAssertionConsumerServices();
        // index 0 (default)
        assertThat(acs.get(0).getIndex()).isEqualTo(0);
        assertThat(acs.get(0).isDefault()).isTrue();
        assertThat(acs.get(0).getLocation())
                .isEqualTo("http://" + spHostname + ':' + server.httpPort() + "/saml/acs/post");
        assertThat(acs.get(0).getBinding()).isEqualTo(SAMLConstants.SAML2_POST_BINDING_URI);
        // index 1
        assertThat(acs.get(1).getIndex()).isEqualTo(1);
        assertThat(acs.get(1).isDefault()).isFalse();
        assertThat(acs.get(1).getLocation())
                .isEqualTo("http://" + spHostname + ':' + server.httpPort() + "/saml/acs/redirect");
        assertThat(acs.get(1).getBinding()).isEqualTo(SAMLConstants.SAML2_REDIRECT_BINDING_URI);
    }

    @Test
    void shouldConsumeAssertion_HttpPost() throws Exception {
        final Response response =
                getAuthResponse("http://" + spHostname + ':' + server.httpPort() + "/saml/acs/post");
        final AggregatedHttpResponse res = sendViaHttpPostBindingProtocol("/saml/acs/post",
                                                                          SAML_RESPONSE, response,
                                                                          idpCredential);

        assertThat(res.status()).isEqualTo(HttpStatus.FOUND);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("/");
    }

    @Test
    void shouldConsumeAssertion_HttpRedirect() throws Exception {
        final Response response =
                getAuthResponse("http://" + spHostname + ':' + server.httpPort() + "/saml/acs/redirect");
        final AggregatedHttpResponse res = sendViaHttpRedirectBindingProtocol("/saml/acs/redirect",
                                                                              SAML_RESPONSE, response,
                                                                              idpCredential);

        assertThat(res.status()).isEqualTo(HttpStatus.FOUND);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("/");
    }

    @Test
    void shouldNotConsumeAssertionWithInvalidSignature_HttpPost() throws Exception {
        shouldNotConsumeAssertion_HttpPost(badIdpCredential);
    }

    @Test
    void shouldNotConsumeAssertionWithInvalidSignature_HttpRedirect() throws Exception {
        shouldNotConsumeAssertion_HttpRedirect(badIdpCredential);
    }

    @Test
    void shouldNotConsumeAssertionWithoutSignature_HttpPost() throws Exception {
        shouldNotConsumeAssertion_HttpPost(null);
        final MessageContext<Response> messageContext = messageContextHolder.get();
        assertThat(messageContext.getMessage().getSignature()).isNull();
    }

    @Test
    void shouldNotConsumeAssertionWithoutSignature_HttpRedirect() throws Exception {
        shouldNotConsumeAssertion_HttpRedirect(null);
    }

    private void shouldNotConsumeAssertion_HttpPost(@Nullable Credential idpCredential) throws Exception {
        final Response response =
                getAuthResponse("http://" + spHostname + ':' + server.httpPort() + "/saml/acs/post");
        final AggregatedHttpResponse res = sendViaHttpPostBindingProtocol("/saml/acs/post",
                                                                          SAML_RESPONSE, response,
                                                                          idpCredential);

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isNull();
    }

    private void shouldNotConsumeAssertion_HttpRedirect(@Nullable Credential idpCredential) throws Exception {
        final Response response =
                getAuthResponse("http://" + spHostname + ':' + server.httpPort() + "/saml/acs/redirect");
        final AggregatedHttpResponse res = sendViaHttpRedirectBindingProtocol("/saml/acs/redirect",
                                                                              SAML_RESPONSE, response,
                                                                              idpCredential);

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isNull();
    }

    private static Response getAuthResponse(String recipient) throws Exception {
        // IdP entity ID
        final Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue("http://idp.example.com/post");

        final Assertion assertion = build(Assertion.DEFAULT_ELEMENT_NAME);
        final Subject subject = build(Subject.DEFAULT_ELEMENT_NAME);
        final SubjectConfirmation subjectConfirmation = build(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        final SubjectConfirmationData data = build(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);

        data.setInResponseTo(requestIdManager.newId());
        data.setNotOnOrAfter(DateTime.now().plusMinutes(1));
        data.setRecipient(recipient);

        subjectConfirmation.setSubjectConfirmationData(data);
        subjectConfirmation.setMethod("urn:oasis:names:tc:SAML:2.0:cm:bearer");

        subject.getSubjectConfirmations().add(subjectConfirmation);

        assertion.setSubject(subject);

        assertion.setIssuer(XMLObjectSupport.cloneXMLObject(issuer));
        assertion.setIssueInstant(DateTime.now());
        assertion.setID(requestIdManager.newId());

        final AuthnStatement authnStatement = build(AuthnStatement.DEFAULT_ELEMENT_NAME);
        authnStatement.setSessionIndex("1");
        assertion.getAuthnStatements().add(authnStatement);

        final Conditions conditions = build(Conditions.DEFAULT_ELEMENT_NAME);
        conditions.setNotBefore(DateTime.now().minusMinutes(1));
        conditions.setNotOnOrAfter(DateTime.now().plusMinutes(1));

        final AudienceRestriction audienceRestriction = build(AudienceRestriction.DEFAULT_ELEMENT_NAME);
        final Audience audience = build(Audience.DEFAULT_ELEMENT_NAME);
        // Set SP entity ID as an audience.
        audience.setAudienceURI(spEntityId);
        audienceRestriction.getAudiences().add(audience);
        conditions.getAudienceRestrictions().add(audienceRestriction);

        assertion.setConditions(conditions);

        sign(assertion, idpCredential, signatureAlgorithm);

        final Response response = build(Response.DEFAULT_ELEMENT_NAME);
        response.getAssertions().add(assertion);

        response.setID(requestIdManager.newId());
        response.setIssuer(issuer);
        response.setIssueInstant(DateTime.now());

        final Status status = build(Status.DEFAULT_ELEMENT_NAME);
        final StatusCode statusCode = build(StatusCode.DEFAULT_ELEMENT_NAME);
        statusCode.setValue(StatusCode.SUCCESS);
        status.setStatusCode(statusCode);
        response.setStatus(status);

        return response;
    }

    @Test
    void shouldConsumeLogoutRequest_HttpPost() throws Exception {
        final LogoutRequest logoutRequest =
                getLogoutRequest("http://" + spHostname + ':' + server.httpPort() + "/saml/slo/post",
                                 "http://idp.example.com/post");

        final AggregatedHttpResponse res = sendViaHttpPostBindingProtocol("/saml/slo/post", SAML_REQUEST,
                                                                          logoutRequest, idpCredential);

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.HTML_UTF_8);

        final Document doc = Jsoup.parse(res.contentUtf8());
        assertThat(doc.body().attr("onLoad")).isEqualTo("document.forms[0].submit()");

        // SAMLResponse will be posted to the IdP's logout response URL.
        final Element form = doc.body().child(0);
        assertThat(form.attr("method")).isEqualTo("post");
        assertThat(form.attr("action")).isEqualTo("http://idp.example.com/saml/slo/post");
        assertThat(form.child(0).attr("name")).isEqualTo(SAML_RESPONSE);
    }

    @Test
    void shouldConsumeLogoutRequest_HttpRedirect() throws Exception {
        final LogoutRequest logoutRequest =
                getLogoutRequest("http://" + spHostname + ':' + server.httpPort() + "/saml/slo/redirect",
                                 "http://idp.example.com/redirect");

        final AggregatedHttpResponse res =
                sendViaHttpRedirectBindingProtocol("/saml/slo/redirect", SAML_REQUEST,
                                                   logoutRequest, idpCredential);

        assertThat(res.status()).isEqualTo(HttpStatus.FOUND);

        // Check the order of the parameters in the quest string.
        final String location = res.headers().get(HttpHeaderNames.LOCATION);
        final Pattern p = Pattern.compile(
                "http://idp\\.example\\.com/saml/slo/redirect\\?" +
                "SAMLResponse=([^&]+)&SigAlg=([^&]+)&Signature=(.+)$");
        assertThat(location).isNotNull();
        assertThat(p.matcher(location).matches()).isTrue();
    }

    @Test
    void shouldNotConsumeLogoutRequestWithInvalidSignature_HttpPost() throws Exception {
        shouldNotConsumeLogoutRequest_HttpPost(badIdpCredential);
    }

    @Test
    void shouldNotConsumeLogoutRequestWithInvalidSignature_HttpRedirect() throws Exception {
        shouldNotConsumeLogoutRequest_HttpRedirect(badIdpCredential);
    }

    @Test
    void shouldNotConsumeLogoutRequestWithoutSignature_HttpPost() throws Exception {
        shouldNotConsumeLogoutRequest_HttpPost(null);
    }

    @Test
    void shouldNotConsumeLogoutRequestWithoutSignature_HttpRedirect() throws Exception {
        shouldNotConsumeLogoutRequest_HttpRedirect(null);
    }

    private void shouldNotConsumeLogoutRequest_HttpPost(@Nullable Credential idpCredential) {
        final LogoutRequest logoutRequest =
                getLogoutRequest("http://" + spHostname + ':' + server.httpPort() + "/saml/slo/post",
                                 "http://idp.example.com/post");

        final AggregatedHttpResponse res = sendViaHttpPostBindingProtocol("/saml/slo/post", SAML_REQUEST,
                                                                          logoutRequest, idpCredential);

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("400 Bad Request");
    }

    private void shouldNotConsumeLogoutRequest_HttpRedirect(@Nullable Credential idpCredential) {
        final LogoutRequest logoutRequest =
                getLogoutRequest("http://" + spHostname + ':' + server.httpPort() + "/saml/slo/redirect",
                                 "http://idp.example.com/redirect");

        final AggregatedHttpResponse res =
                sendViaHttpRedirectBindingProtocol("/saml/slo/redirect", SAML_REQUEST,
                                                   logoutRequest, idpCredential);

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isNull();
    }

    private static LogoutRequest getLogoutRequest(String destination, String issuerId) {
        final LogoutRequest logoutRequest = build(LogoutRequest.DEFAULT_ELEMENT_NAME);

        logoutRequest.setID(requestIdManager.newId());
        logoutRequest.setDestination(destination);

        final Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(issuerId);
        logoutRequest.setIssuer(issuer);
        logoutRequest.setIssueInstant(DateTime.now());

        final NameID nameID = build(NameID.DEFAULT_ELEMENT_NAME);
        nameID.setFormat(SamlNameIdFormat.EMAIL.urn());

        logoutRequest.setNameID(nameID);

        return logoutRequest;
    }

    private AggregatedHttpResponse sendViaHttpPostBindingProtocol(
            String path, String paramName, SignableSAMLObject signableObj, @Nullable Credential idpCredential) {
        final String encoded;
        if (idpCredential != null) {
            encoded = toSignedBase64(signableObj, idpCredential, signatureAlgorithm);
        } else {
            // Generate an unsigned message.
            final String messageStr = nodeToString(serialize(signableObj));
            encoded = Base64.getEncoder().encodeToString(messageStr.getBytes(StandardCharsets.UTF_8));
        }

        final HttpRequest req = HttpRequest.of(HttpMethod.POST, path, MediaType.FORM_DATA,
                                               QueryParams.of(paramName, encoded).toQueryString());
        return client.execute(req).aggregate().join();
    }

    private AggregatedHttpResponse sendViaHttpRedirectBindingProtocol(
            String path, String paramName, SAMLObject samlObject, @Nullable Credential idpCredential) {

        final QueryParamsBuilder params = QueryParams.builder();
        params.add(paramName, toDeflatedBase64(samlObject));
        params.add(SIGNATURE_ALGORITHM, signatureAlgorithm);
        final String input = params.toQueryString();
        if (idpCredential != null) {
            params.add(SIGNATURE, generateSignature(idpCredential, signatureAlgorithm, input));
        } else {
            // Generate an unsigned message.
        }

        final HttpRequest req = HttpRequest.of(HttpMethod.POST, path, MediaType.FORM_DATA,
                                               params.toQueryString());
        return client.execute(req).aggregate().join();
    }
}
