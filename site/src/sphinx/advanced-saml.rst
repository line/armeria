.. _`OpenSAML`: https://wiki.shibboleth.net/confluence/display/OS30/Home
.. _`Security Assertion Markup Language (SAML)`: https://en.wikipedia.org/wiki/Security_Assertion_Markup_Language

.. _advanced-saml:

SAML Single Sign-On
===================

.. note::

    Visit `armeria-examples <https://github.com/line/armeria-examples>`_ to find a fully working example.

What is SAML?
-------------

`Security Assertion Markup Language (SAML)`_ is an open standard for exchanging authentication and authorization
data between an identity provider and a service provider. In this protocol, a service provider is an endpoint
which provides a web service to an end user, and an identity provider is in charge of authenticating an end
user with information sent by the service provider.
Armeria currently provides OpenSAML_ based ``armeria-saml`` module in order to support the integration with
an identity provider from a service provider's point of view.

Configuring your server as a service provider
---------------------------------------------

The first step to configure a service provider is adding ``armeria-saml`` to your dependencies.

For Maven:

.. parsed-literal::
    :class: highlight-xml

    <dependency>
        <groupId>com.linecorp.armeria</groupId>
        <artifactId>saml</artifactId>
        <version>\ |release|\ </version>
    </dependency>

For Gradle:

.. parsed-literal::
    :class: highlight-groovy

    dependencies {
        compile 'com.linecorp.armeria:saml:\ |release|\ '
    }

After that, you need to prepare your keystore file which contains a key pair for signing and encryption
of a SAML message. Also, you need to import the certificate of your identity provider into the keystore
which contains your key pairs. In this example, we are using a free identity provider service hosted by
`SSOCircle <https://www.ssocircle.com/en/>`_ in order to authenticate an end user. The following commands
may help you to get a keystore.

.. code-block:: bash

    # Generate new key pairs as alias 'signing' and 'encryption'.
    keytool -genkeypair -keystore sample.jks -storepass 'N5^X[hvG' -keyalg rsa -sigalg sha1withrsa \
      -dname 'CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown' -alias signing
    keytool -genkeypair -keystore sample.jks -storepass 'N5^X[hvG' -keyalg rsa -sigalg sha1withrsa \
      -dname 'CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown' -alias encryption

    # Import a certificate into the keystore as alias 'https://idp.ssocircle.com', which is the entity ID
    # of the SSOCircle. You can make 'ssocircle.crt' file with the certificate from
    # 'https://www.ssocircle.com/en/idp-tips-tricks/public-idp-configuration/'.
    keytool -importcert -keystore sample.jks -storepass 'N5^X[hvG' -file ssocircle.crt \
      -alias 'https://idp.ssocircle.com'

Finally, you need to create your :api:`SamlServiceProvider` with a :api:`SamlServiceProviderBuilder`, and
attach it to your :api:`Server`.

.. code-block:: java

    SamlServiceProvider ssp = new SamlServiceProviderBuilder()
            // Specify information about your keystore. The keystore contains two key pairs
            // which are identified as 'signing' and 'encryption'.
            .credentialResolver(new KeyStoreCredentialResolverBuilder("sample.jks")
                                        .type("PKCS12")
                                        .password("N5^X[hvG")
                                        // You need to specify your key pair and its password here.
                                        .addKeyPassword("signing", "N5^X[hvG")
                                        .addKeyPassword("encryption", "N5^X[hvG")
                                        .build())
            // Specify the entity ID of this service provider. You can specify what you want.
            .entityId("your-sp-id")
            .hostname("your-service-domain-name")
            // Specify an authorizer in order to authenticate a request.
            .authorizer(new Authorizer<HttpRequest>() { ... })
            // Speicify an SAML single sign-on handler which sends a response to an end user
            // after he or she is authenticated or not.
            .ssoHandler(new SamlSingleSignOnHandler() { ... })
            // Specify the signature algorithm of your key.
            .signatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA)
            .idp()
            // Specify the entity ID of the identity provider. It can be found from the metadata of
            // the identity provider.
            .entityId("https://idp.ssocircle.com")
            // Specify the endpoint that is supposed to send an authentication request.
            .ssoEndpoint(ofHttpPost("https://idp.ssocircle.com:443/sso/SSOPOST/metaAlias/publicidp"))
            .and()
            .build();

    Server server = new ServerBuilder()
            .https(8443)
            // Configure TLS with your key and certificate.
            .tls(new File("your-certificate-file-path"), new File("your-key-file-path"))
            // Decorate you service with SAML decorator.
            .annotatedService("/", new MyService(), ssp.newSamlDecorator())
            // Add SAML service to your server which handles a SAML response and a metadata request.
            .service(ssp.newSamlService())
            .build();

How to handle the authentication response
-----------------------------------------

``armeria-saml`` provides :api:`SamlSingleSignOnHandler` to handle the response from an identity provider.
It consists of ``loginSucceeded()`` and ``loginFailed()`` methods which handle the response,
and ``beforeInitiatingSso()`` which handles a request. In most cases, you only need to write the two methods
which handle the response, but if you want to send data to your identity provider and get it back
with a response, you need to implement ``beforeInitiatingSso()`` method.

The following example shows a simple implementation of the :api:`SamlSingleSignOnHandler`. In this example,
if an authentication is succeeded, an email address is retrieved from the response by referring to a ``name ID``
element in the assertion, then it is sent to the end user via ``Set-Cookie`` header. It means that your
:api:`Authorizer` can identify an authenticated session with a ``Cookie`` header in the following requests,
like ``MyAuthorizer`` in this example.

.. code-block:: java

    class MySamlSingleSignOnHandler implements SamlSingleSignOnHandler {
        @Override
        public HttpResponse loginSucceeded(ServiceRequestContext ctx, AggregatedHttpMessage req,
                                           MessageContext<Response> message, @Nullable String sessionIndex,
                                           @Nullable String relayState) {
            final Response response = message.getMessage();
            final String username = response.getAssertions().stream()
                                            .map(s -> s.getSubject().getNameID())
                                            .filter(id -> id.getFormat().equals(SamlNameIdFormat.EMAIL.urn()))
                                            .map(NameIDType::getValue)
                                            .findFirst()
                                            .orElse(null);
            if (username == null) {
                return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.HTML_UTF_8,
                                       "<html><body>Username is not found.</body></html>");
            }

            // Note that you MUST NOT use this example in a real world application. You may consider encoding
            // the value using JSON Web Tokens to prevent tempering.
            final Cookie cookie = new DefaultCookie("username", username);
            cookie.setHttpOnly(true);
            cookie.setDomain("localhost");
            cookie.setMaxAge(60);
            cookie.setPath("/");
            return HttpResponse.of(
                    HttpHeaders.of(HttpStatus.OK)
                               .contentType(MediaType.HTML_UTF_8)
                               .add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.LAX.encode(cookie)),
                    HttpData.ofUtf8("<html><body onLoad=\"window.location.href='/welcome'\"></body></html>"));
        }

        @Override
        public HttpResponse loginFailed(ServiceRequestContext ctx, AggregatedHttpMessage req,
                                        @Nullable MessageContext<Response> message, Throwable cause) {
            return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.HTML_UTF_8,
                                   "<html><body>Login failed.</body></html>");
        }
    }

    class MyAuthorizer implements Authorizer<HttpRequest> {
        @Override
        public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest data) {
            // Note that you MUST NOT use this example in a real world application. You have to perform
            // proper validation in your application.
            final String cookie = data.headers().get(HttpHeaderNames.COOKIE);
            if (cookie == null) {
                return CompletableFuture.completedFuture(false);
            }

            final boolean authenticated = ServerCookieDecoder.LAX.decode(cookie).stream().anyMatch(
                    c -> "username".equals(c.name()) && !Strings.isNullOrEmpty(c.value()));
            return CompletableFuture.completedFuture(authenticated);
        }
    }

.. note::

    The above implementation is just an example that shows you how to handle the response, so it is recommended
    that you write your own :api:`SamlSingleSignOnHandler` according to your authentication system.

What services are automatically configured
------------------------------------------

``armeria-saml`` module automatically adds SAML services to your server with the following default paths:

- ``/saml/acs/post`` and ``/saml/acs/redirect``

  - SAML assertion consumer services for HTTP Post binding and HTTP Redirect binding. These services are invoked
    by an identity provider when it responds to an authentication request received from your service.

- ``/saml/slo/post`` and ``/saml/slo/redirect``

  - SAML single logout services for HTTP Post binding and HTTP Redirect binding. These services may be invoked
    by an identity provider when it performs global logout.

- ``/saml/metadata``

  - SAML metadata service. In the metadata, the endpoints for assertion consumer services and single logout
    services are specified by ``md:AssertionConsumerService`` and ``md:SingleLogoutService`` elements
    respectively. The certificates of the ``signing`` and ``encryption`` key pair are also included.

.. note::

    In order for your service to act as a service provider, you need to register your service to your identity
    provider, and providing your metadata is the easiest way to do that. You can get your metadata from
    ``https://your-service-domain-name:your-service-port/saml/metadata``.
