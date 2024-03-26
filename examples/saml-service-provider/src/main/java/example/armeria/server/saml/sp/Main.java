package example.armeria.server.saml.sp;

import static com.linecorp.armeria.server.saml.SamlEndpoint.ofHttpPost;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

import org.opensaml.security.credential.CredentialResolver;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.saml.KeyStoreCredentialResolverBuilder;
import com.linecorp.armeria.server.saml.SamlServiceProvider;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * Configures an identity provider with <a href="https://idp.ssocircle.com/meta-idp.xml">
     * the metadata of the SSOCircle</a>. You must <a href="https://idp.ssocircle.com/sso/hos/SPMetaInter.jsp">
     * register</a> this service provider, which we are configuring here, to the SSOCircle.
     * You can get the metadata of this service provider from {@code https://localhost:8443/saml/metadata}
     * after starting this server.
     *
     * <p>The {@code signing} and {@code encryption} key pair in the keystore {@code sample.jks} can be
     * generated with the following commands:
     * <pre>{@code
     * $ keytool -genkeypair -keystore sample.jks -storepass 'N5^X[hvG' -keyalg rsa -sigalg sha1withrsa \
     *     -dname 'CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown' -alias signing \
     *     -deststoretype pkcs12
     *
     * $ keytool -genkeypair -keystore sample.jks -storepass 'N5^X[hvG' -keyalg rsa -sigalg sha1withrsa \
     *     -dname 'CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown' -alias encryption \
     *     -deststoretype pkcs12
     * }</pre>
     *
     * <p>The certificate of the SSOCircle can be imported into the keystore with the following command.
     * You can specify its alias as same as its entity ID so that you do not need to specify the alias
     * when building a {@link SamlServiceProvider}. You can make {@code ssocircle.crt} file with
     * the certificate from <a href="https://www.ssocircle.com/en/idp-tips-tricks/public-idp-configuration/">
     * Public IDP Configuration</a> of SSOCircle.
     * <pre>{@code
     * $ keytool -importcert -keystore sample.jks -storepass 'N5^X[hvG' -file ssocircle.crt \
     *     -alias 'https://idp.ssocircle.com' -deststoretype pkcs12
     * }</pre>
     */
    private static SamlServiceProvider samlServiceProvider() throws IOException, GeneralSecurityException {
        final MyAuthHandler authHandler = new MyAuthHandler();

        // Specify information about your keystore.
        // The keystore contains two key pairs, which are identified as 'signing' and 'encryption'.
        final CredentialResolver credentialResolver =
                new KeyStoreCredentialResolverBuilder(Main.class.getClassLoader(), "sample.jks")
                        .type("PKCS12")
                        .password("N5^X[hvG")
                        // You need to specify your key pair and its password here.
                        .keyPassword("signing", "N5^X[hvG")
                        .keyPassword("encryption", "N5^X[hvG")
                        .build();

        return SamlServiceProvider.builder()
                                  .credentialResolver(credentialResolver)
                                  // Specify the entity ID of this service provider.
                                  // You can specify what you want.
                                  .entityId("armeria-sp")
                                  .hostname("localhost")
                                  // Specify an authorizer in order to authenticate a request.
                                  .authorizer(authHandler)
                                  // Speicify an SAML single sign-on handler
                                  // which sends a response to an end user
                                  // after he or she is authenticated or not.
                                  .ssoHandler(authHandler)
                                  // Specify the signature algorithm of your key.
                                  .signatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA)
                                  // The following information is from
                                  // https://idp.ssocircle.com/meta-idp.xml.
                                  .idp()
                                  // Specify the entity ID of the identity provider.
                                  // It can be found from the metadata of the identity provider.
                                  .entityId("https://idp.ssocircle.com")
                                  // Specify the endpoint that is supposed to send an authentication request.
                                  .ssoEndpoint(ofHttpPost(
                                          "https://idp.ssocircle.com:443/sso/SSOPOST/metaAlias/publicidp"))
                                  .and()
                                  .build();
    }

    public static void main(String[] args) throws Exception {
        final SamlServiceProvider ssp = samlServiceProvider();
        final Server server =
                Server.builder()
                      .https(8443)
                      // You can add this certificate to your trust store
                      // in order to make your web browser happy.
                      .tls(new File(ClassLoader.getSystemResource("localhost.crt").toURI()),
                           new File(ClassLoader.getSystemResource("localhost.key").toURI()))
                      // Decorate you service with SAML decorator.
                      .annotatedService("/", new MyService(), ssp.newSamlDecorator())
                      // Add SAML service to your server which handles a SAML response and a metadata request.
                      .service(ssp.newSamlService())
                      .build();

        server.closeOnJvmShutdown();

        server.start().join();
        logger.info("Server has been started.");
    }
}
