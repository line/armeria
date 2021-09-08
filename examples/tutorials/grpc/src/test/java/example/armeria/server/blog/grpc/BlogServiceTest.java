package example.armeria.server.blog.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.server.blog.grpc.Blog.BlogId;
import example.armeria.server.blog.grpc.Blog.BlogPost;
import example.armeria.server.blog.grpc.Blog.Sort;
import example.armeria.server.blog.grpc.BlogServiceGrpc.BlogServiceBlockingStub;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;

class BlogServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new BlogService())
                                  .build());
        }
    };

    static BlogServiceBlockingStub client;

    @BeforeAll
    static void beforeAll() {
        client = Clients.newClient(server.httpUri(GrpcSerializationFormats.PROTO),
                                   BlogServiceBlockingStub.class);
    }

    @Test
    @Order(1)
    void createBlogPost() throws JsonProcessingException {
        final BlogPost newBlogPost = BlogPost.newBuilder()
                                             .setTitle("My first blog")
                                             .setContent("Hello Armeria!")
                                             .build();
        final BlogPost created = client.createBlogPost(newBlogPost);
        assertThat(created.getTitle()).isEqualTo(newBlogPost.getTitle());
        assertThat(created.getContent()).isEqualTo(newBlogPost.getContent());
    }

    @Test
    @Order(2)
    void getBlogPost() throws JsonProcessingException {
        final BlogPost blogPost = client.getBlogPost(BlogId.newBuilder().setId(0).build());

        assertThat(blogPost.getTitle()).isEqualTo("My first blog");
        assertThat(blogPost.getContent()).isEqualTo("Hello Armeria!");
    }

    @Test
    @Order(3)
    void getBlogPosts() throws JsonProcessingException {
        final BlogPost newBlogPost = BlogPost.newBuilder()
                                             .setTitle("My second blog")
                                             .setContent("Armeria is awesome!")
                                             .build();
        client.createBlogPost(newBlogPost);
        final Iterator<BlogPost> iterator = client.getBlogPosts(Sort.getDefaultInstance());
        final List<BlogPost> blogPosts = new ArrayList<>();
        iterator.forEachRemaining(blogPosts::add);

        assertThat(blogPosts).hasSize(2);
        final BlogPost firstBlog = blogPosts.get(0);
        assertThat(firstBlog.getTitle()).isEqualTo("My first blog");
        assertThat(firstBlog.getContent()).isEqualTo("Hello Armeria!");

        final BlogPost secondBlog = blogPosts.get(1);
        assertThat(secondBlog.getTitle()).isEqualTo("My second blog");
        assertThat(secondBlog.getContent()).isEqualTo("Armeria is awesome!");
    }

    @Test
    @Order(4)
    void updateBlogPosts() throws JsonProcessingException {
        final BlogPost blogPost = BlogPost.newBuilder()
                                          .setId(0)
                                          .setTitle("My first blog")
                                          .setContent("Hello awesome Armeria!")
                                          .build();
        final BlogPost updated = client.updateBlogPost(blogPost);
        assertThat(updated.getId()).isZero();
        assertThat(updated.getTitle()).isEqualTo("My first blog");
        assertThat(updated.getContent()).isEqualTo("Hello Armeria!");
    }

    @Test
    @Order(5)
    void badRequestExceptionHandlerWhenTryingDeleteMissingBlogPost() throws JsonProcessingException {
        final Throwable exception = catchThrowable(() -> {
            client.deleteBlogPost(BlogId.newBuilder().setId(100).build());
        });
        final StatusRuntimeException statusException = (StatusRuntimeException) exception;
        assertThat(statusException.getStatus().getCode()).isEqualTo(Code.NOT_FOUND);
        assertThat(statusException).hasMessageContaining("The blog post does not exist. ID: 100");
    }
}
