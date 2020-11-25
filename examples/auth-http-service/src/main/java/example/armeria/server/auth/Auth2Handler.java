package example.armeria.server.auth;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.Authorizer;

final class Auth2Handler implements Authorizer<HttpRequest> {

    /**
     * Checks whether the request has right permission to access the service. In this example,
     * a {@code AUTHORIZATION}
     * header is used to hold the information. If a {@code AUTHORIZATION} key exists in the header,
     * the request is treated as authenticated.
     */
    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req) {
        //Get request's headers token
        final Function<? super RequestHeaders, OAuth2Token> oAuth2TokenFunction = AuthTokenExtractors.oAuth2();
        final OAuth2Token oAuth2Token = oAuth2TokenFunction.apply(req.headers());
        //get a token
        final OAuth2Token passToken = OAuth2Token.of("accessToken");
        System.out.println(passToken.accessToken() + "____________");
        System.out.println(req.headers());
        return CompletableFuture.supplyAsync(
                () -> passToken.accessToken().equals(oAuth2Token.accessToken()));
    }
}
