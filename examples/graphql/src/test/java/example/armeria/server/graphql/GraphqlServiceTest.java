package example.armeria.server.graphql;

import static example.armeria.server.graphql.Main.configureService;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class GraphqlServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            configureService(sb);
        }
    };

    @ParameterizedTest
    @CsvSource({
            "{user(id: 1) {name}},hero",
            "{user(id: 2) {name}},human",
            "{user(id: 3) {name}},droid"
    })
    void testUserDataFetch(String query, String expected) {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.GRAPHQL, query)
                                               .build();
        final AggregatedHttpResponse response = client().execute(request)
                                                        .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8()).node("data.user.name").isEqualTo(expected);
    }

    private static WebClient client() {
        return WebClient.of(server.httpUri());
    }
}
