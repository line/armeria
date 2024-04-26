package com.linecorp.armeria.server.cors;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.*;
import com.linecorp.armeria.server.annotation.*;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorators;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

public class CorsServerErrorHanderTest {
    private static final ClientFactory clientFactory = ClientFactory.ofDefault();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService myService = new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                protected HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                protected HttpResponse doOptions(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            };
            addCorsServiceWithException(sb, myService, "/cors_status_exception", HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR));
            addCorsServiceWithException(sb, myService, "/cors_response_exception", HttpResponseException.of(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)));
        }
    };

    private static void addCorsServiceWithException(ServerBuilder sb, HttpService myService, String pathPattern, Exception exception) {
        sb.service(pathPattern, myService.decorate(CorsService.builder("http://example.com").allowRequestMethods(HttpMethod.POST, HttpMethod.GET).allowRequestHeaders("allow_request_header").exposeHeaders("expose_header_1", "expose_header_2").preflightResponseHeader("x-preflight-cors", "Hello CORS").newDecorator()).decorate(new DecoratingHttpServiceFunction() {
            @Override
            public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) throws Exception {
                throw exception;
            }
        }));
    }

    static WebClient client() {
        return WebClient.builder(server.httpUri()).factory(clientFactory).build();
    }

    static AggregatedHttpResponse request(WebClient client, HttpMethod method, String path, String origin, String requestMethod) {
        return client.execute(RequestHeaders.of(method, path, HttpHeaderNames.ACCEPT, "utf-8", HttpHeaderNames.ORIGIN, origin, HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, requestMethod)).aggregate().join();
    }

    static AggregatedHttpResponse preflightRequest(WebClient client, String path, String origin, String requestMethod) {
        return request(client, HttpMethod.OPTIONS, path, origin, requestMethod);
    }

    void testCorsHeaderWithException(String path) {
        final WebClient client = client();
        final AggregatedHttpResponse response = preflightRequest(client, "/cors_status_exception", "http://example.com", "GET");
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS)).isEqualTo("allow_request_header");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("http://example.com");
    }

    @Test
    void testCorsHeaderWhenStatusException() {
        testCorsHeaderWithException("/cors_status_exception");
    }

    @Test
    void testCorsHeaderWhenResponseException() {
        testCorsHeaderWithException("/cors_response_exception");
    }
}
