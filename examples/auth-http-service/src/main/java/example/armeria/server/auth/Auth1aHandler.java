package example.armeria.server.auth;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.Authorizer;

final class Auth1aHandler implements Authorizer<HttpRequest> {

    /**
     * Checks whether the request has right permission to access the service. In this example,
     * a {@code AUTHORIZATION}
     * header is used to hold the information. If a {@code AUTHORIZATION} key exists in the header,
     * the request is treated as authenticated.
     */
    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req) {
        //Get request's headers token
        final Function<? super RequestHeaders, OAuth1aToken> oAuth1aTokenFunction = AuthTokenExtractors
                .oAuth1a();
        final OAuth1aToken oAuth1aToken = oAuth1aTokenFunction.apply(req.headers());
        //get a token
        final OAuth1aToken passToken = OAuth1aToken.builder()
                .realm("dummy_realm")
                .consumerKey("dummy_consumer_key@#$!")
                .token("dummy_oauth1a_token")
                .signatureMethod("dummy")
                .signature("dummy_signature")
                .timestamp("0")
                .nonce("dummy_nonce")
                .version("1.0")
                .build();

        return CompletableFuture.supplyAsync(
                () -> passToken.signature().equals(oAuth1aToken.signature()) &&
                        passToken.consumerKey().equals(oAuth1aToken.consumerKey()));
    }
}
