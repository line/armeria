package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.decorator.RequestTimeout;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class RequestTimeoutAnnotationTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/myService", new MyAnnotationService());

        }
    };

    static final long timeoutMillis = 1230;
    static final long timeoutSeconds = 4560;

    public static class MyAnnotationService {
        @Get("/timeoutMillis")
        @RequestTimeout(timeoutMillis)
        public String timeoutMillis(ServiceRequestContext ctx, HttpRequest req) {
            AnnotatedServiceTest.validateContextAndRequest(ctx, req);
            return Long.toString(ctx.requestTimeoutMillis());
        }

        @Get("/timeoutSeconds")
        @RequestTimeout(value = timeoutSeconds, unit = TimeUnit.SECONDS)
        public String timeoutSeconds(ServiceRequestContext ctx, HttpRequest req) {
            AnnotatedServiceTest.validateContextAndRequest(ctx, req);
            return Long.toString(ctx.requestTimeoutMillis());
        }

        @Get("/subscriberIsInitialized")
        public boolean subscriberIsInitialized(ServiceRequestContext ctx, HttpRequest req) {
            AnnotatedServiceTest.validateContextAndRequest(ctx, req);
            return ((DefaultServiceRequestContext) ctx).requestCancellationScheduler().isInitialized();
        }
    }

    @Test
    public void testRequestTimeoutSet() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        AggregatedHttpResponse response;

        response = client.execute(RequestHeaders.of(HttpMethod.GET, "/myService/timeoutMillis"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(Long.parseLong(response.contentUtf8())).isEqualTo(timeoutMillis);

        response = client.execute(RequestHeaders.of(HttpMethod.GET, "/myService/timeoutSeconds"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(Long.parseLong(response.contentUtf8()))
                .isEqualTo(TimeUnit.SECONDS.toMillis(timeoutSeconds));
    }

    @Test
    public void testCancellationSchedulerInit() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/myService/timeoutMillis"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(Boolean.parseBoolean(response.contentUtf8())).isEqualTo(true);
    }

}
