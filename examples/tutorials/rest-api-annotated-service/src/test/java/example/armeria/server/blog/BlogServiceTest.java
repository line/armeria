package example.armeria.server.blog;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@TestMethodOrder(OrderAnnotation.class)
class BlogServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new BlogService());
        }
    };

    @Test
    @Order(1)
    void createBlogPost() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequest request = createBlogPostRequest(Map.of("title", "My first blog",
                                                                 "content", "Hello Armeria!"));
        final AggregatedHttpResponse res = client.execute(request).aggregate().join();

        final Map<String, Object> expected = Map.of("id", 0,
                                                    "title", "My first blog",
                                                    "content", "Hello Armeria!");

        assertThatJson(res.contentUtf8()).whenIgnoringPaths("createdAt", "modifiedAt")
                                         .isEqualTo(mapper.writeValueAsString(expected));
    }

    @Test
    @Order(2)
    void getBlogPost() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/blogs/0").aggregate().join();
        final Map<String, Object> expected = Map.of("id", 0,
                                                    "title", "My first blog",
                                                    "content", "Hello Armeria!");

        assertThatJson(res.contentUtf8()).whenIgnoringPaths("createdAt", "modifiedAt")
                                         .isEqualTo(mapper.writeValueAsString(expected));
    }

    @Test
    @Order(3)
    void getBlogPosts() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequest request = createBlogPostRequest(Map.of("title", "My second blog",
                                                                 "content", "Armeria is awesome!"));
        client.execute(request).aggregate().join();
        final AggregatedHttpResponse res = client.get("/blogs").aggregate().join();
        final List<Map<String, Object>> expected = List.of(
                Map.of("id", 1,
                       "title", "My second blog",
                       "content", "Armeria is awesome!"),
                Map.of("id", 0,
                       "title", "My first blog",
                       "content", "Hello Armeria!"));
        assertThatJson(res.contentUtf8()).whenIgnoringPaths("[*].createdAt", "[*].modifiedAt")
                                         .isEqualTo(mapper.writeValueAsString(expected));
    }

    @Test
    @Order(4)
    void updateBlogPosts() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final Map<String, Object> updatedContent = Map.of("id", 0,
                                                          "title", "My first blog",
                                                          "content", "Hello awesome Armeria!");
        final HttpRequest updateBlogPostRequest =
                HttpRequest.builder()
                           .put("/blogs/0")
                           .content(MediaType.JSON_UTF_8, mapper.writeValueAsString(updatedContent))
                           .build();
        client.execute(updateBlogPostRequest).aggregate().join();
        final AggregatedHttpResponse res = client.get("/blogs/0").aggregate().join();
        final Map<String, Object> expected = Map.of("id", 0,
                                                    "title", "My first blog",
                                                    "content", "Hello awesome Armeria!");

        assertThatJson(res.contentUtf8()).whenIgnoringPaths("createdAt", "modifiedAt")
                                         .isEqualTo(mapper.writeValueAsString(expected));
    }

    @Test
    @Order(5)
    void badRequestExceptionHandlerWhenTryingDeleteMissingBlogPost() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.delete("/blogs/100").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThatJson(res.contentUtf8()).isEqualTo("{\"error\":\"The blog post does not exist. id: 100\"}");
    }

    private static HttpRequest createBlogPostRequest(Map<String, String> content)
            throws JsonProcessingException {
        return HttpRequest.builder()
                          .post("/blogs")
                          .content(MediaType.JSON_UTF_8, mapper.writeValueAsString(content))
                          .build();
    }
}
