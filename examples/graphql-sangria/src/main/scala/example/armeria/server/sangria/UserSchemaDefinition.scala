package example.armeria.server.sangria

import example.armeria.server.sangria.UserRepo.data
import sangria.macros.derive.deriveObjectType
import sangria.schema._

case class User(id: String, name: String)

object Users {
  private val UserType: ObjectType[Unit, User] = deriveObjectType[Unit, User]()
  private val Id: Argument[String] = Argument("id", StringType)
  private val Query: ObjectType[UserRepo, Unit] = ObjectType(
    "Query",
    fields[UserRepo, Unit](
      Field(
        "user",
        OptionType(UserType),
        description = Some("Returns a product with specific `id`."),
        arguments = Id :: Nil,
        resolve = c => c.ctx.findById(c.arg(Id))))
  )

  val UserSchema: Schema[UserRepo, Unit] = Schema(Query)
}

class UserRepo {
  def findById(id: String): Option[User] = data.get(id)
}

object UserRepo {
  private val data = Map("1" -> User("1", "hero"), "2" -> User("2", "human"), "3" -> User("3", "droid"))
}
