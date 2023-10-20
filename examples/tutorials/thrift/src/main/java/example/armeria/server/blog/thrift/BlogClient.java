package example.armeria.server.blog.thrift;

import java.net.URI;
import java.util.List;

import org.apache.thrift.TException;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.thrift.ThriftClients;

import example.armeria.blog.thrift.BlogPost;
import example.armeria.blog.thrift.BlogService;
import example.armeria.blog.thrift.CreateBlogPostRequest;
import example.armeria.blog.thrift.DeleteBlogPostRequest;
import example.armeria.blog.thrift.GetBlogPostRequest;
import example.armeria.blog.thrift.ListBlogPostsRequest;
import example.armeria.blog.thrift.UpdateBlogPostRequest;

public final class BlogClient {

    private final BlogService.Iface blogService;

    BlogClient(URI uri, String path) {
        blogService = ThriftClients.builder(uri)
                                   .path(path)
                                   .decorator(LoggingClient.newDecorator())
                                   .build(BlogService.Iface.class);
    }

    BlogPost createBlogPost(String title, String content) throws TException {
        final CreateBlogPostRequest request =
                new CreateBlogPostRequest().setTitle(title)
                                           .setContent(content);
        return blogService.createBlogPost(request);
    }

    BlogPost getBlogPost(int id) throws TException {
        final GetBlogPostRequest request =
                new GetBlogPostRequest().setId(id);
        return blogService.getBlogPost(request);
    }

    List<BlogPost> listBlogPosts(boolean descending) throws TException {
        return blogService.listBlogPosts(new ListBlogPostsRequest().setDescending(descending))
                          .getBlogs();
    }

    BlogPost updateBlogPost(int id, String newTitle, String newContent) throws TException {
        final UpdateBlogPostRequest request = new UpdateBlogPostRequest().setId(id)
                                                                         .setTitle(newTitle)
                                                                         .setContent(newContent);
        return blogService.updateBlogPost(request);
    }

    void deleteBlogPost(int id) throws TException {
        final DeleteBlogPostRequest request = new DeleteBlogPostRequest().setId(id);
        blogService.deleteBlogPost(request);
    }
}
