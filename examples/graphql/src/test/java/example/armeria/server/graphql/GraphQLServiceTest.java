package example.armeria.server.graphql;

import static example.armeria.server.graphql.Main.newServer;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Charsets;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.Server;

class GraphQLServiceTest {

    private static Server server;
    private static WebClient client;

    @BeforeAll
    static void beforeClass() {
        server = newServer(0);
        server.start().join();
        client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort());
    }

    @AfterAll
    static void afterClass() {
        if (server != null) {
            server.stop().join();
        }
        if (client != null) {
            client.options().factory().close();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "{user(id: 1) {name}},hero",
            "{user(id: 2) {name}},human",
            "{user(id: 3) {name}},droid"
    })
    void testUserDataFetch(String query, String expected) throws Exception {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .path("/graphql")
                                                     .method(HttpMethod.POST)
                                                     .contentType(MediaType.GRAPHQL)
                                                     .build();
        final AggregatedHttpResponse response = client.execute(headers, query, Charsets.UTF_8)
                                                      .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8()).node("data.user.name").isEqualTo(expected);
    }
}
