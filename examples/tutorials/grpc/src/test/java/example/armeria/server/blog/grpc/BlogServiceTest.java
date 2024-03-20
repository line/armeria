package example.armeria.server.blog.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.blog.grpc.BlogPost;
import example.armeria.blog.grpc.BlogServiceGrpc.BlogServiceBlockingStub;
import example.armeria.blog.grpc.CreateBlogPostRequest;
import example.armeria.blog.grpc.DeleteBlogPostRequest;
import example.armeria.blog.grpc.GetBlogPostRequest;
import example.armeria.blog.grpc.ListBlogPostsRequest;
import example.armeria.blog.grpc.ListBlogPostsResponse;
import example.armeria.blog.grpc.UpdateBlogPostRequest;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;

@TestMethodOrder(OrderAnnotation.class)
class BlogServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new BlogService())
                                  .exceptionHandler(new GrpcExceptionHandler())
                                  .build());
        }
    };

    static BlogServiceBlockingStub client;
    static BlogServiceBlockingStub decoratedClient;

    @BeforeAll
    static void beforeAll() {
        client = GrpcClients.newClient(server.httpUri(),
                                       BlogServiceBlockingStub.class);

        decoratedClient = GrpcClients.builder(server.httpUri())
                                     .serializationFormat(GrpcSerializationFormats.JSON)
                                     .maxResponseMessageLength(10000)
                                     .decorator(LoggingClient.newDecorator())
                                     .build(BlogServiceBlockingStub.class);
    }

    @Test
    @Order(1)
    void createBlogPost() throws JsonProcessingException {
        final CreateBlogPostRequest request = CreateBlogPostRequest.newBuilder()
                                                                   .setTitle("My first blog")
                                                                   .setContent("Hello Armeria!")
                                                                   .build();
        final BlogPost response = client.createBlogPost(request);
        assertThat(response.getTitle()).isEqualTo(request.getTitle());
        assertThat(response.getContent()).isEqualTo(request.getContent());
    }

    @Test
    @Order(2)
    void getBlogPost() throws JsonProcessingException {
        final BlogPost blogPost = client.getBlogPost(GetBlogPostRequest.newBuilder().setId(0).build());

        assertThat(blogPost.getTitle()).isEqualTo("My first blog");
        assertThat(blogPost.getContent()).isEqualTo("Hello Armeria!");
    }

    @Test
    @Order(2)
    void getInvalidBlogPost() throws JsonProcessingException {
        final Throwable exception = catchThrowable(() -> {
            client.getBlogPost(GetBlogPostRequest.newBuilder().setId(Integer.MAX_VALUE).build());
        });
        final StatusRuntimeException statusException = (StatusRuntimeException) exception;
        assertThat(statusException.getStatus().getCode()).isEqualTo(Code.NOT_FOUND);
        assertThat(statusException)
                .hasMessageContaining("The blog post does not exist. ID: " + Integer.MAX_VALUE);
    }

    @Test
    @Order(3)
    void listBlogPosts() throws JsonProcessingException {
        final CreateBlogPostRequest newBlogPost = CreateBlogPostRequest.newBuilder()
                                                                       .setTitle("My second blog")
                                                                       .setContent("Armeria is awesome!")
                                                                       .build();
        client.createBlogPost(newBlogPost);
        final ListBlogPostsResponse
                response = client.listBlogPosts(ListBlogPostsRequest.newBuilder()
                                                                    .setDescending(false)
                                                                    .build());

        final List<BlogPost> blogs = response.getBlogsList();
        assertThat(blogs).hasSize(2);
        final BlogPost firstBlog = blogs.get(0);
        assertThat(firstBlog.getTitle()).isEqualTo("My first blog");
        assertThat(firstBlog.getContent()).isEqualTo("Hello Armeria!");

        final BlogPost secondBlog = blogs.get(1);
        assertThat(secondBlog.getTitle()).isEqualTo("My second blog");
        assertThat(secondBlog.getContent()).isEqualTo("Armeria is awesome!");
    }

    @Test
    @Order(4)
    void updateBlogPosts() throws JsonProcessingException {
        final UpdateBlogPostRequest request = UpdateBlogPostRequest.newBuilder()
                                                                   .setId(0)
                                                                   .setTitle("My first blog")
                                                                   .setContent("Hello awesome Armeria!")
                                                                   .build();
        final BlogPost updated = client.updateBlogPost(request);
        assertThat(updated.getId()).isZero();
        assertThat(updated.getTitle()).isEqualTo("My first blog");
        assertThat(updated.getContent()).isEqualTo("Hello awesome Armeria!");
    }

    @Test
    @Order(5)
    void badRequestExceptionHandlerWhenTryingDeleteMissingBlogPost() throws JsonProcessingException {
        final Throwable exception = catchThrowable(() -> {
            client.deleteBlogPost(DeleteBlogPostRequest.newBuilder().setId(100).build());
        });
        final StatusRuntimeException statusException = (StatusRuntimeException) exception;
        assertThat(statusException.getStatus().getCode()).isEqualTo(Code.NOT_FOUND);
        assertThat(statusException).hasMessageContaining("The blog post does not exist. ID: 100");
    }
}
