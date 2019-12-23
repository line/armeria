package example.dropwizard.armeria.services.http;

import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

class HelloServiceTest {

    private static HelloService service;

    @BeforeAll
    static void setUp() {
        service = new HelloService();
    }

    @SuppressWarnings("MultipleExceptionsDeclaredOnTestMethod")
    @Test
    void testHelloService_JSON() throws ExecutionException, InterruptedException {
        // When
        final HttpResponse res = service.helloJson();

        // Then
        final AggregatedHttpResponse aggregatedHttpResponse = res.aggregate().get();
        assertThat(aggregatedHttpResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedHttpResponse.contentType()).isEqualTo(JSON_UTF_8);
        assertThat(aggregatedHttpResponse.contentUtf8()).isEqualTo("{ \"name\": \"Armeria\" }");
    }

    @Test
    void testHelloService_plainText() {
        final String res = service.helloText();
        assertThat(res).isEqualTo("Armeria");
    }
}
