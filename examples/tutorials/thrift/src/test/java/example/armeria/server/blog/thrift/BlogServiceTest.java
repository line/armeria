package example.armeria.server.blog.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;

import org.apache.thrift.TException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.logging.LoggingRpcClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.blog.thrift.BlogNotFoundException;
import example.armeria.blog.thrift.BlogPost;
import example.armeria.blog.thrift.BlogService;
import example.armeria.blog.thrift.CreateBlogPostRequest;
import example.armeria.blog.thrift.DeleteBlogPostRequest;
import example.armeria.blog.thrift.GetBlogPostRequest;
import example.armeria.blog.thrift.ListBlogPostsRequest;
import example.armeria.blog.thrift.ListBlogPostsResponse;
import example.armeria.blog.thrift.UpdateBlogPostRequest;

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

    static BlogService.Iface client;

    @BeforeAll
    static void beforeAll() {
        client = ThriftClients.builder(server.httpUri())
                              .path("/thrift")
                              .rpcDecorator(LoggingRpcClient.newDecorator())
                              .build(BlogService.Iface.class);
    }

    @Test
    @Order(1)
    void createBlogPost() throws TException {
        final CreateBlogPostRequest request = new CreateBlogPostRequest()
                .setTitle("My first blog")
                .setContent("Hello Armeria!");
        final BlogPost response = client.createBlogPost(request);
        assertThat(response.getId()).isGreaterThanOrEqualTo(0);
        assertThat(response.getTitle()).isEqualTo(request.getTitle());
        assertThat(response.getContent()).isEqualTo(request.getContent());
    }

    @Test
    @Order(2)
    void getBlogPost() throws TException {
        final BlogPost blogPost = client.getBlogPost(new GetBlogPostRequest().setId(0));

        assertThat(blogPost.getTitle()).isEqualTo("My first blog");
        assertThat(blogPost.getContent()).isEqualTo("Hello Armeria!");
    }

    @Test
    @Order(3)
    void getInvalidBlogPost() {
        final Throwable exception = catchThrowable(() -> {
            client.getBlogPost(new GetBlogPostRequest().setId(Integer.MAX_VALUE));
        });
        assertThat(exception).isInstanceOf(BlogNotFoundException.class)
                .extracting("reason")
                .asString()
                .isEqualTo("The blog post does not exist. ID: " + Integer.MAX_VALUE);
    }

    @Test
    @Order(4)
    void listBlogPosts() throws TException {
        final CreateBlogPostRequest newBlogPost = new CreateBlogPostRequest()
                .setTitle("My second blog")
                .setContent("Armeria is awesome!");
        client.createBlogPost(newBlogPost);
        final ListBlogPostsResponse
                response = client.listBlogPosts(new ListBlogPostsRequest()
                                                        .setDescending(false));

        final List<BlogPost> blogs = response.getBlogs();
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
        final UpdateBlogPostRequest request = new UpdateBlogPostRequest()
                .setId(0)
                .setTitle("My first blog")
                .setContent("Hello awesome Armeria!");
        final BlogPost updated = client.updateBlogPost(request);
        assertThat(updated.getId()).isZero();
        assertThat(updated.getTitle()).isEqualTo("My first blog");
        assertThat(updated.getContent()).isEqualTo("Hello awesome Armeria!");
    }

    @Test
    @Order(6)
    void badRequestExceptionHandlerWhenTryingDeleteMissingBlogPost() {
        final Throwable exception = catchThrowable(() -> {
            client.deleteBlogPost(new DeleteBlogPostRequest().setId(100));
        });
        assertThat(exception).isInstanceOf(BlogNotFoundException.class)
                             .extracting("reason")
                             .asString()
                             .isEqualTo("The blog post does not exist. ID: 100");
    }
}
