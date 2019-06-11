package example.armeria.server.annotated;

import static example.armeria.server.annotated.Main.newServer;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.Server;

public class AnnotatedHttpServiceTest {

    private static Server server;
    private static HttpClient client;

    @BeforeClass
    public static void beforeClass() {
        server = newServer(0);
        server.start().join();
        client = HttpClient.of("http://127.0.0.1:" + server.activePort().get().localAddress().getPort());
    }

    @AfterClass
    public static void afterClass() {
        if (server != null) {
            server.stop().join();
        }
        if (client != null) {
            client.factory().close();
        }
    }

    @Test
    public void testPathPatternService() {
        AggregatedHttpResponse res;

        res = client.get("/pathPattern/path/armeria").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("path: armeria");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        res = client.get("/pathPattern/regex/armeria").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("regex: armeria");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        res = client.get("/pathPattern/glob/armeria").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("glob: armeria");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    public void testInjectionService() {
        AggregatedHttpResponse res;

        res = client.get("/injection/param/armeria/1?gender=male").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isArray()
                                         .ofLength(3)
                                         .thatContains("armeria")
                                         .thatContains(1)
                                         .thatContains("MALE");

        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.GET, "/injection/header")
                                                     .add(HttpHeaderNames.of("x-armeria-text"), "armeria")
                                                     .add(HttpHeaderNames.of("x-armeria-sequence"), "1")
                                                     .add(HttpHeaderNames.of("x-armeria-sequence"), "2")
                                                     .add(HttpHeaderNames.COOKIE, "a=1")
                                                     .add(HttpHeaderNames.COOKIE, "b=1").build();

        res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isArray()
                                         .ofLength(3)
                                         .thatContains("armeria")
                                         .thatContains(Arrays.asList(1, 2))
                                         .thatContains(Arrays.asList("a", "b"));
    }

    @Test
    public void testMessageConverterService() {
        AggregatedHttpResponse res;
        String body;

        // JSON
        for (final String path : Arrays.asList("/messageConverter/node/node",
                                               "/messageConverter/node/obj",
                                               "/messageConverter/obj/obj",
                                               "/messageConverter/obj/future")) {
            res = client.execute(RequestHeaders.of(HttpMethod.POST, path,
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8),
                                 "{\"name\":\"armeria\"}").aggregate().join();

            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentType().is(MediaType.JSON_UTF_8)).isTrue();

            body = res.contentUtf8();
            assertThatJson(body).node("result").isStringEqualTo("success");
            assertThatJson(body).node("from").isStringEqualTo("armeria");
        }

        // custom(text protocol)
        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/messageConverter/custom",
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8),
                             "armeria").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType().is(MediaType.PLAIN_TEXT_UTF_8)).isTrue();
        assertThat(res.contentUtf8()).isEqualTo("success:armeria");
    }

    @Test
    public void testExceptionHandlerService() {
        AggregatedHttpResponse res;

        res = client.get("/exception/locallySpecific").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        res = client.get("/exception/locallyGeneral").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        res = client.get("/exception/globallyGeneral").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.FORBIDDEN);

        // IllegalArgumentException
        res = client.get("/exception/default").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        // HttpStatusException
        res = client.get("/exception/default/200").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        res = client.get("/exception/default/409").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.CONFLICT);
    }
}
