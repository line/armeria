package example.armeria.server.auth;

import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.Authorizer;

final class MyAuthHandler implements Authorizer<HttpRequest> {

    /**
     * Checks whether the request has right permission to access the service. In this example,
     * a {@code AUTHORIZATION}
     * header is used to hold the information. If a {@code AUTHORIZATION} key exists in the header,
     * the request is treated as authenticated.
     */
    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req) {
        return CompletableFuture.supplyAsync(
                () -> "token".equals(req.headers().get(AUTHORIZATION)));
    }
}
