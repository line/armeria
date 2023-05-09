package example.armeria.server.blog.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;

import org.apache.thrift.TException;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.blog.thrift.BlogNotFoundException;
import example.armeria.blog.thrift.BlogPost;

@TestMethodOrder(OrderAnnotation.class)
class BlogServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/thrift", THttpService.builder()
                                              .exceptionHandler(new BlogServiceExceptionHandler())
                                              .addService(new BlogServiceImpl())
                                              .build());
        }
    };

    @Test
    @Order(1)
    void createBlogPost() throws TException {
        final BlogClient client = new BlogClient(server.httpUri(), "/thrift");
        final BlogPost response = client.createBlogPost("My first blog", "Hello Armeria!");
        assertThat(response.getId()).isGreaterThanOrEqualTo(0);
        assertThat(response.getTitle()).isEqualTo("My first blog");
        assertThat(response.getContent()).isEqualTo("Hello Armeria!");
    }

    @Test
    @Order(2)
    void getBlogPost() throws TException {
        final BlogClient client = new BlogClient(server.httpUri(), "/thrift");
        final BlogPost blogPost = client.getBlogPost(0);

        assertThat(blogPost.getTitle()).isEqualTo("My first blog");
        assertThat(blogPost.getContent()).isEqualTo("Hello Armeria!");
    }

    @Test
    @Order(3)
    void getInvalidBlogPost() {
        final BlogClient client = new BlogClient(server.httpUri(), "/thrift");
        final Throwable exception = catchThrowable(() -> {
            client.getBlogPost(Integer.MAX_VALUE);
        });
        assertThat(exception).isInstanceOf(BlogNotFoundException.class)
                .extracting("reason")
                .asString()
                .isEqualTo("The blog post does not exist. ID: " + Integer.MAX_VALUE);
    }

    @Test
    @Order(4)
    void listBlogPosts() throws TException {
        final BlogClient client = new BlogClient(server.httpUri(), "/thrift");
        client.createBlogPost("My second blog", "Armeria is awesome!");

        final List<BlogPost> blogs = client.listBlogPosts(false);
        assertThat(blogs).hasSize(2);
        final BlogPost firstBlog = blogs.get(0);
        assertThat(firstBlog.getTitle()).isEqualTo("My first blog");
        assertThat(firstBlog.getContent()).isEqualTo("Hello Armeria!");

        final BlogPost secondBlog = blogs.get(1);
        assertThat(secondBlog.getTitle()).isEqualTo("My second blog");
        assertThat(secondBlog.getContent()).isEqualTo("Armeria is awesome!");
    }

    @Test
    @Order(5)
    void updateBlogPosts() throws TException {
        final BlogClient client = new BlogClient(server.httpUri(), "/thrift");
        final BlogPost updated = client.updateBlogPost(0, "My first blog", "Hello awesome Armeria!");
        assertThat(updated.getId()).isZero();
        assertThat(updated.getTitle()).isEqualTo("My first blog");
        assertThat(updated.getContent()).isEqualTo("Hello awesome Armeria!");
    }

    @Test
    @Order(6)
    void badRequestExceptionHandlerWhenTryingDeleteMissingBlogPost() {
        final BlogClient client = new BlogClient(server.httpUri(), "/thrift");
        final Throwable exception = catchThrowable(() -> {
            client.deleteBlogPost(100);
        });
        assertThat(exception).isInstanceOf(BlogNotFoundException.class)
                             .extracting("reason")
                             .asString()
                             .isEqualTo("The blog post does not exist. ID: 100");
    }
}
