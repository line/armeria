package com.linecorp.armeria.server.sangria

import sangria.execution.deferred.{Fetcher, HasId}
import sangria.schema._

import scala.concurrent.Future

/**
 * Defines a GraphQL schema for the current project
 */
object SchemaDefinition {

  /**
   * Resolves the lists of characters. These resolutions are batched and
   * cached for the duration of a query.
   */
  val characters: Fetcher[
    CharacterRepo,
    Character with Product with Serializable,
    Character with Product with Serializable,
    String] = Fetcher.caching((ctx: CharacterRepo, ids: Seq[String]) =>
    Future.successful(ids.flatMap(id => ctx.getHuman(id).orElse(ctx.getDroid(id)))))(HasId(_.id))

  val EpisodeEnum: EnumType[Episode.Value] = EnumType(
    "Episode",
    Some("One of the films in the Star Wars Trilogy"),
    List(
      EnumValue("NEWHOPE", value = Episode.NEWHOPE, description = Some("Released in 1977.")),
      EnumValue("EMPIRE", value = Episode.EMPIRE, description = Some("Released in 1980.")),
      EnumValue("JEDI", value = Episode.JEDI, description = Some("Released in 1983."))
    )
  )

  val Character: InterfaceType[CharacterRepo, Character] =
    InterfaceType(
      "Character",
      "A character in the Star Wars Trilogy",
      () =>
        fields[CharacterRepo, Character](
          Field("id", StringType, Some("The id of the character."), resolve = _.value.id),
          Field("name", OptionType(StringType), Some("The name of the character."), resolve = _.value.name),
          Field(
            "friends",
            ListType(Character),
            Some("The friends of the character, or an empty list if they have none."),
            resolve = (ctx: Context[CharacterRepo, Character]) => characters.deferSeqOpt(ctx.value.friends)
          ),
          Field(
            "appearsIn",
            OptionType(ListType(OptionType(EpisodeEnum))),
            Some("Which movies they appear in."),
            resolve = _.value.appearsIn.map(e => Some(e)))
        )
    )

  val Human: ObjectType[CharacterRepo, Human] =
    ObjectType(
      "Human",
      "A humanoid creature in the Star Wars universe.",
      interfaces[CharacterRepo, Human](Character),
      fields[CharacterRepo, Human](
        Field("id", StringType, Some("The id of the human."), resolve = _.value.id),
        Field("name", OptionType(StringType), Some("The name of the human."), resolve = _.value.name),
        Field(
          "friends",
          ListType(Character),
          Some("The friends of the human, or an empty list if they have none."),
          resolve = ctx => characters.deferSeqOpt(ctx.value.friends)
        ),
        Field(
          "appearsIn",
          OptionType(ListType(OptionType(EpisodeEnum))),
          Some("Which movies they appear in."),
          resolve = _.value.appearsIn.map(e => Some(e))),
        Field(
          "homePlanet",
          OptionType(StringType),
          Some("The home planet of the human, or null if unknown."),
          resolve = _.value.homePlanet)
      )
    )

  val Droid: ObjectType[CharacterRepo, Droid] = ObjectType(
    "Droid",
    "A mechanical creature in the Star Wars universe.",
    interfaces[CharacterRepo, Droid](Character),
    fields[CharacterRepo, Droid](
      Field("id", StringType, Some("The id of the droid."), resolve = _.value.id),
      Field(
        "name",
        OptionType(StringType),
        Some("The name of the droid."),
        resolve = ctx => Future.successful(ctx.value.name)),
      Field(
        "friends",
        ListType(Character),
        Some("The friends of the droid, or an empty list if they have none."),
        resolve = ctx => characters.deferSeqOpt(ctx.value.friends)
      ),
      Field(
        "appearsIn",
        OptionType(ListType(OptionType(EpisodeEnum))),
        Some("Which movies they appear in."),
        resolve = _.value.appearsIn.map(e => Some(e))),
      Field(
        "primaryFunction",
        OptionType(StringType),
        Some("The primary function of the droid."),
        resolve = _.value.primaryFunction)
    )
  )

  val ID: Argument[String] = Argument("id", StringType, description = "id of the character")

  val EpisodeArg: Argument[Option[Episode.Value]] = Argument(
    "episode",
    OptionInputType(EpisodeEnum),
    description =
      "If omitted, returns the hero of the whole saga. If provided, returns the hero of that particular episode."
  )

  val LimitArg: Argument[Int] = Argument("limit", OptionInputType(IntType), defaultValue = 20)
  val OffsetArg: Argument[Int] = Argument("offset", OptionInputType(IntType), defaultValue = 0)

  val Query: ObjectType[CharacterRepo, Unit] = ObjectType(
    "Query",
    fields[CharacterRepo, Unit](
      Field(
        "hero",
        Character,
        arguments = EpisodeArg :: Nil,
        deprecationReason = Some("Use `human` or `droid` fields instead"),
        resolve = (ctx) => ctx.ctx.getHero(ctx.arg(EpisodeArg))
      ),
      Field("human", OptionType(Human), arguments = ID :: Nil, resolve = ctx => ctx.ctx.getHuman(ctx.arg(ID))),
      Field(
        "droid",
        Droid,
        arguments = ID :: Nil,
        resolve = Projector((ctx, f) => ctx.ctx.getDroid(ctx.arg(ID)).get))
    )
  )

  val StarWarsSchema: Schema[CharacterRepo, Unit] = Schema(Query)
}
