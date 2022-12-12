package example.armeria.server.blog;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestObject;

public final class BlogService {

    private final Map<Integer, BlogPost> blogPosts = new ConcurrentHashMap<>();

    /**
     * Creates a {@link BlogPost} from an {@link HttpRequest}. The {@link HttpRequest} is
     * converted into {@link BlogPost} by the {@link BlogPostRequestConverter}.
     */
    @Post("/blogs")
    @RequestConverter(BlogPostRequestConverter.class)
    public HttpResponse createBlogPost(BlogPost blogPost) {
        // Use a map to store the blog. In real world, you should use a database.
        blogPosts.put(blogPost.getId(), blogPost);

        // Send the created blog post as the response.
        // We can add additional property such as a url of
        // the created blog post.(e.g. "http://tutorial.com/blogs/0") to respect the Rest API.
        return HttpResponse.ofJson(blogPost);
    }

    /**
     * Retrieves a {@link BlogPost} whose {@link BlogPost#getId()} is the {@code :id} in the path parameter.
     */
    @Get("/blogs/:id")
    public HttpResponse getBlogPost(@Param int id) {
        final BlogPost blogPost = blogPosts.get(id);
        return HttpResponse.ofJson(blogPost);
    }

    /**
     * Retrieves all {@link BlogPost}s created so far in the reverse chronological order .
     */
    @Get("/blogs")
    @ProducesJson
    public Iterable<BlogPost> getBlogPosts(@Param @Default("true") boolean descending) {
        if (descending) {
            return blogPosts.entrySet()
                            .stream()
                            .sorted(Collections.reverseOrder(Comparator.comparingInt(Entry::getKey)))
                            .map(Entry::getValue).collect(Collectors.toList());
        }
        return blogPosts.values().stream().collect(Collectors.toList());
    }

    /**
     * Updates the {@link BlogPost} whose {@link BlogPost#getId()} is the {@code :id} in the path parameter.
     */
    @Put("/blogs/:id")
    public HttpResponse updateBlogPost(@Param int id, @RequestObject BlogPost blogPost) {
        final BlogPost oldBlogPost = blogPosts.get(id);
        if (oldBlogPost == null) {
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        }
        final BlogPost newBlogPost = new BlogPost(id, blogPost.getTitle(), blogPost.getContent(),
                                                  oldBlogPost.getCreatedAt(), blogPost.getCreatedAt());
        blogPosts.put(id, newBlogPost);
        return HttpResponse.ofJson(newBlogPost);
    }

    /**
     * Deletes the {@link BlogPost} whose {@link BlogPost#getId()} is the {@code :id} in the path parameter.
     */
    @Blocking
    @Delete("/blogs/:id")
    @ExceptionHandler(BadRequestExceptionHandler.class)
    public HttpResponse deleteBlogPost(@Param int id) {
        final BlogPost removed = blogPosts.remove(id);
        if (removed == null) {
            throw new IllegalArgumentException("The blog post does not exist. ID: " + id);
            // Or we can simply return a NOT_FOUND response.
            // return HttpResponse.of(HttpStatus.NOT_FOUND);
        }
        return HttpResponse.of(HttpStatus.NO_CONTENT);
    }
}
