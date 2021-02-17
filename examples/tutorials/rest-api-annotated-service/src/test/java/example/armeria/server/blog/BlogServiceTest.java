package example.armeria.server.blog;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class BlogServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new BlogService());
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void createBlogPost() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequest request = createBlogPostRequest(ImmutableMap.of("title", "My first blog",
                                                                          "content", "Hello Armeria!"));
        final AggregatedHttpResponse res = client.execute(request).aggregate().join();

        final Map<String, Object> expected = ImmutableMap.of("id", 0,
                                                             "title", "My first blog",
                                                             "content", "Hello Armeria!");

        assertThatJson(res.contentUtf8()).whenIgnoringPaths("createdAt", "modifiedAt")
                                         .isEqualTo(mapper.writeValueAsString(expected));

    }

    @Test
    void badRequestExceptionHandlerWhenMissingTitle() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequest request = createBlogPostRequest(ImmutableMap.of("content", "Hello Armeria!"));
        final AggregatedHttpResponse res = client.execute(request).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThatJson(res.contentUtf8()).isEqualTo("{\"error\":\"title is missing!\"}");
    }

    @Test
    void getBlogPost() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequest request = createBlogPostRequest(ImmutableMap.of("title", "My first blog",
                                                                          "content", "Hello Armeria!"));
        client.execute(request).aggregate().join();
        final AggregatedHttpResponse res = client.get("/blogs/0").aggregate().join();
        final Map<String, Object> expected = ImmutableMap.of("id", 0,
                                                             "title", "My first blog",
                                                             "content", "Hello Armeria!");

        assertThatJson(res.contentUtf8()).whenIgnoringPaths("createdAt", "modifiedAt")
                                         .isEqualTo(mapper.writeValueAsString(expected));
    }

    @Test
    void getBlogPosts() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        HttpRequest request = createBlogPostRequest(ImmutableMap.of("title", "My first blog",
                                                                    "content", "Hello Armeria!"));
        client.execute(request).aggregate().join();
        request = createBlogPostRequest(ImmutableMap.of("title", "My second blog",
                                                        "content", "Armeria is awesome!"));
        client.execute(request).aggregate().join();
        final AggregatedHttpResponse res = client.get("/blogs").aggregate().join();
        final List<Map<String, Object>> expected = ImmutableList.of(
                ImmutableMap.of("id", 1,
                                "title", "My second blog",
                                "content", "Armeria is awesome!"),
                ImmutableMap.of("id", 0,
                                "title", "My first blog",
                                "content", "Hello Armeria!"));
        assertThatJson(res.contentUtf8()).whenIgnoringPaths("[*].createdAt", "[*].modifiedAt")
                                         .isEqualTo(mapper.writeValueAsString(expected));
    }

    @Test
    void updateBlogPosts() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequest createBlogPostRequest =
                createBlogPostRequest(ImmutableMap.of("title", "My first blog",
                                                      "content", "Hello Armeria!"));
        client.execute(createBlogPostRequest).aggregate().join();

        final Map<String, Object> updatedContent = ImmutableMap.of("id", 0,
                                                                   "title", "My first blog",
                                                                   "content", "Hello awesome Armeria!");
        final HttpRequest updateBlogPostRequest =
                HttpRequest.builder()
                           .put("/blogs/0")
                           .content(MediaType.JSON_UTF_8, mapper.writeValueAsString(updatedContent))
                           .build();
        client.execute(updateBlogPostRequest).aggregate().join();
        final AggregatedHttpResponse res = client.get("/blogs/0").aggregate().join();
        final Map<String, Object> expected = ImmutableMap.of("id", 0,
                                                             "title", "My first blog",
                                                             "content", "Hello awesome Armeria!");

        assertThatJson(res.contentUtf8()).whenIgnoringPaths("createdAt", "modifiedAt")
                                         .isEqualTo(mapper.writeValueAsString(expected));
    }

    private static HttpRequest createBlogPostRequest(ImmutableMap<String, String> content)
            throws JsonProcessingException {
        return HttpRequest.builder()
                          .post("/blogs")
                          .content(MediaType.JSON_UTF_8, mapper.writeValueAsString(content))
                          .build();
    }
}
