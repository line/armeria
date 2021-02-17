package example.armeria.server.blog;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.RequestConverter;

public final class BlogService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AtomicInteger idGenerator = new AtomicInteger();

    private final Map<Integer, BlogPost> blogPosts = new ConcurrentHashMap<>();

    /**
     * Creates a {@link BlogPost} from an {@link HttpRequest}. The {@link HttpRequest} is
     * converted into {@link JsonNode} by the {@link JacksonRequestConverterFunction}.
     */
    @Post("/blogs")
    @ExceptionHandler(BadRequestExceptionHandler.class)
    public HttpResponse createBlogPost(JsonNode jsonNode) throws JsonProcessingException {
        // Use integer for simplicity.
        final int id = idGenerator.getAndIncrement();
        final String title = stringValue(jsonNode, "title");
        final String content = stringValue(jsonNode, "content");
        final BlogPost blogPost = new BlogPost(id, title, content);

        // Use a map to store the blog. In real world, you should use a database.
        blogPosts.put(id, blogPost);

        // Send the created blog post as the response.
        // We can add additional property such as a url(e.g. "http://tutorial.com/blogs/1")
        // to respect the Rest API.
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, mapper.writeValueAsBytes(blogPost));
    }

    /**
     * Retrieves a {@link BlogPost} whose {@link BlogPost#id()} is the {@code :id} in the path parameter.
     */
    @Get("/blogs/:id")
    public HttpResponse getBlogPost(@Param int id) throws JsonProcessingException {
        final BlogPost blogPost = blogPosts.get(id);
        final byte[] content = mapper.writeValueAsBytes(blogPost);
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, content);
    }

    /**
     * Retrieves all {@link BlogPost}s created so far in the reverse chronological order .
     */
    @Get("/blogs")
    @ProducesJson
    public Iterable<BlogPost> getBlogPosts() throws JsonProcessingException {
        return blogPosts.entrySet()
                        .stream()
                        .sorted(Collections.reverseOrder(Comparator.comparingInt(Entry::getKey)))
                        .map(Entry::getValue).collect(toImmutableList());
    }

    /**
     * Updates the {@link BlogPost} whose {@link BlogPost#id()} is the {@code :id} in the path parameter.
     */
    @Put("/blogs/:id")
    @RequestConverter(BlogPostRequestConverter.class)
    public HttpResponse updateBlogPost(@Param int id, BlogPost blogPost) throws JsonProcessingException {
        final BlogPost oldBlogPost = blogPosts.get(id);
        if (oldBlogPost == null) {
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        }
        final BlogPost newBlogPost = new BlogPost(id, blogPost.title(), blogPost.content(),
                                                  oldBlogPost.createdAt(), blogPost.createdAt());
        blogPosts.put(id, newBlogPost);
        final byte[] bytes = mapper.writeValueAsBytes(newBlogPost);
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, bytes);
    }

    /**
     * Deletes the {@link BlogPost} whose {@link BlogPost#id()} is the {@code :id} in the path parameter.
     */
    @Blocking
    @Delete("/blogs/:id")
    public HttpResponse deleteBlogPost(@Param int id) {
        final BlogPost removed = blogPosts.remove(id);
        if (removed == null) {
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        }
        return HttpResponse.of(HttpStatus.NO_CONTENT);
    }

    static String stringValue(JsonNode jsonNode, String field) {
        final JsonNode value = getValue(jsonNode, field);
        return value.textValue();
    }

    static int intValue(JsonNode jsonNode, String field) {
        final JsonNode value = getValue(jsonNode, field);
        return value.intValue();
    }

    private static JsonNode getValue(JsonNode jsonNode, String field) {
        final JsonNode value = jsonNode.get(field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is missing!");
        }
        return value;
    }
}
