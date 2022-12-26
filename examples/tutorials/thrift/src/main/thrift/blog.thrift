namespace java example.armeria.blog.thrift

struct CreateBlogPostRequest {
    1: string title;
    2: string content;
}

struct GetBlogPostRequest {
    1: i32 id;
}

struct ListBlogPostsRequest {
    1: bool descending;
}

struct ListBlogPostsResponse {
    1: list<BlogPost> blogs;
}

struct UpdateBlogPostRequest {
    1: i32 id;
    2: string title;
    3: string content;
}

struct DeleteBlogPostRequest {
    1: i32 id;
}

struct BlogPost {
    1: i32 id;
    2: string title;
    3: string content;
    4: i64 createdAt;
    5: i64 modifiedAt;
}

exception BlogNotFoundException {
    1: required string reason
}

service BlogService {
  BlogPost createBlogPost(1:CreateBlogPostRequest request),
  BlogPost getBlogPost(1:GetBlogPostRequest request) throws (1:BlogNotFoundException e),
  ListBlogPostsResponse listBlogPosts(1:ListBlogPostsRequest request),
  BlogPost updateBlogPost(1:UpdateBlogPostRequest request) throws (1:BlogNotFoundException e),
  void deleteBlogPost(1:DeleteBlogPostRequest request) throws (1:BlogNotFoundException e),
}
