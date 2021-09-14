package example.armeria.server.saml.sp;

import static com.linecorp.armeria.server.saml.SamlUtil.getNameId;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.armeria.server.saml.SamlNameIdFormat;
import com.linecorp.armeria.server.saml.SamlSingleSignOnHandler;

/**
 * An example of an {@link Authorizer} and a {@link SamlSingleSignOnHandler}.
 *
 * <p>Note that the implementation in this class is purely for a demonstration purpose.
 * You should perform proper authorization in a real world application.
 */
final class MyAuthHandler implements Authorizer<HttpRequest>, SamlSingleSignOnHandler {
    private static final Logger logger = LoggerFactory.getLogger(MyAuthHandler.class);

    /**
     * Checks whether the request has right permission to access the service. In this example, a {@code Cookie}
     * header is used to hold the information. If a {@code username} key exists in the {@code Cookie} header,
     * the request is treated as authenticated.
     */
    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest data) {
        final String cookie = data.headers().get(HttpHeaderNames.COOKIE);
        if (cookie == null) {
            return CompletableFuture.completedFuture(false);
        }

        final boolean authenticated = Cookie.fromCookieHeader(cookie).stream().anyMatch(
                c -> "username".equals(c.name()) && !Strings.isNullOrEmpty(c.value()));
        return CompletableFuture.completedFuture(authenticated);
    }

    /**
     * Invoked when the SAML authentication process is finished and a user is authenticated. You can get
     * information about the authenticated user from the {@link Response}, especially his or her login name.
     * In this example, an email address is used as a login name. The login name is transferred to a web
     * browser via {@code Set-Cookie} header.
     */
    @Override
    public HttpResponse loginSucceeded(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                       MessageContext<Response> message, @Nullable String sessionIndex,
                                       @Nullable String relayState) {
        final NameID nameId = getNameId(message.getMessage(), SamlNameIdFormat.EMAIL);
        final String username = nameId != null ? nameId.getValue() : null;
        if (username == null) {
            return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.HTML_UTF_8,
                                   "<html><body>Username is not found.</body></html>");
        }

        logger.info("{} user '{}' has been logged in.", ctx, username);

        final Cookie cookie = Cookie.builder("username", username)
                                    .httpOnly(true)
                                    .domain("localhost")
                                    .maxAge(60)
                                    .path("/")
                                    .build();
        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                                              .contentType(MediaType.HTML_UTF_8)
                                              .cookies(cookie).build(),
                               HttpData.ofUtf8(
                                       "<html><body onLoad=\"window.location.href='/welcome'\"></body></html>"));
    }

    /**
     * Invoked when a single sign-on request is rejected from the identity provider.
     */
    @Override
    public HttpResponse loginFailed(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                    @Nullable MessageContext<Response> message, Throwable cause) {
        return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.HTML_UTF_8,
                               "<html><body>Login failed.</body></html>");
    }
}
