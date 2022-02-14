package server.blog

import com.linecorp.armeria.common.{HttpResponse, HttpStatus}
import com.linecorp.armeria.scala.implicits.*
import com.linecorp.armeria.server.annotation.*

import scala.collection.mutable

/**
 *
 * @param blogPosts
 */
case class BlogService(blogPosts: mutable.Map[Int, BlogPost] = scala.collection.concurrent.TrieMap[Int, BlogPost]()):

  @Post("/blogs")
  @RequestConverter(classOf[BlogPostRequestConverter])
  def createBlogPost(blogPost: BlogPost): HttpResponse =
    blogPosts.put(blogPost.id, blogPost)
    HttpResponse.ofJson(blogPost)

  @Get("/blogs/:id")
  def getBlogPost(@Param id: Int): HttpResponse =
    HttpResponse.ofJson(blogPosts.get(id))

  @Get("/blogs")
  @ProducesJson
  def getBlogPosts(@Param @Default("true") descending: Boolean): Iterable[BlogPost] =
    if descending then
      blogPosts.toList.sortBy(_._1).reverse.map(_._2)
    else
      blogPosts.values

  @Put("/blogs/:id")
  def updateBlogPost(@Param id: Int, @RequestObject blogPost: BlogPost): HttpResponse =
    val oldBlogPost = blogPosts.get(id).orNull
    if oldBlogPost == null then
      HttpResponse.of(HttpStatus.NOT_FOUND)
    else
      val newBlogPost = blogPost.copy(id = id, createAt = oldBlogPost.createAt)
      blogPosts.put(id, newBlogPost)
      HttpResponse.ofJson(newBlogPost)

  @Blocking
  @Delete("/blogs/:id")
  @ExceptionHandler(classOf[BadRequestExceptionHandler])
  def deleteBlogPost(@Param id: Int): HttpResponse =
    val removed = blogPosts.remove(id).orNull
    HttpResponse.of(HttpStatus.NO_CONTENT)
