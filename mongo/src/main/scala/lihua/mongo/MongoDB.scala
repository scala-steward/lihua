package lihua
package mongo

import cats.{Functor}
import com.typesafe.config.{Config, ConfigFactory}
import reactivemongo.api._
import reactivemongo.play.json.collection.JSONCollection
import cats.effect.{Async, IO, LiftIO}

import scala.concurrent.{ExecutionContext, Future}
import net.ceedubs.ficus.Ficus._
import cats.effect.implicits._
import cats.implicits._
import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import reactivemongo.core.nodeset.Authenticate

import scala.util.Try

/**
  * A MongoDB instance from config
  * Should be created one per application
  */
class MongoDB[F[_]: Functor : LiftIO] private(private[mongo] val config: MongoDB.MongoConfig, connection: MongoConnection) {

  private def database(databaseName: String)(implicit ec: ExecutionContext): F[DB] = {
    val dbConfigO = config.dbs.get(s"$databaseName")
    val name = dbConfigO.flatMap(_.name).getOrElse(databaseName)
    toF(connection.database(name))
  }

  def collection(dbName: String, collectionName: String)
            (implicit ec: ExecutionContext): F[JSONCollection] = {

    val collectionConfig = config.dbs.get(dbName).flatMap(_.collections.get(collectionName))
    val name = collectionConfig.flatMap(_.name).getOrElse(collectionName)
    val readPreference = collectionConfig.flatMap(_.readPreference)
    database(dbName).map(
      db => new JSONCollection(db, name, db.failoverStrategy, readPreference.getOrElse(ReadPreference.primary))
    )

  }
  protected def toF[B](f : => Future[B])(implicit ec: ExecutionContext) : F[B] =
    IO.fromFuture(IO(f)).liftIO
}


object MongoDB {

  def apply[F[_]](rootConfig: Config, cryptO: Option[Crypt[F]] = None)
                 (implicit F: Async[F],
        sh: ShutdownHook = ShutdownHook.ignore): F[MongoDB[F]] = {

      for {
        config <- F.fromTry(Try{
                    rootConfig.as[MongoConfig]("mongoDB")
                  })

        auths  <- authOf(config, cryptO)

        _ <- if (config.hosts.isEmpty)
              F.raiseError(new MongoDBConfigurationException("mongoDB.hosts must be set in the conf"))
             else F.unit

        d <-  F.delay {
                val d = MongoDriver(rootConfig.withFallback(ConfigFactory.load("default-reactive-mongo.conf")))
                sh.onShutdown(d.close())
                d
              }

      } yield {
        val connection = d.connection(
          nodes = config.hosts,
          authentications = auths.toSeq,
          options = MongoConnectionOptions(
            sslEnabled = config.sslEnabled,
            authenticationDatabase = config.authSource,
            authMode = config.authMode
          )
        )
        new MongoDB(config, connection)
      }
    }

  private[mongo] def authOf[F[_]](config: MongoConfig, cryptO: Option[Crypt[F]])
                          (implicit F: Async[F]) : F[Option[Authenticate]] =
    config.credential.traverse { c =>
      cryptO.fold(F.pure(c.password))(_.decrypt(c.password)).map(Authenticate(config.authSource.getOrElse("admin"), c.username, _))
    }

  class MongoDBConfigurationException(msg: String) extends Exception(msg)

  case class MongoConfig(
    hosts: List[String] = Nil,
    sslEnabled: Boolean = false,
    authSource: Option[String] = None,
    credential: Option[Credential] = None,
    authMode: AuthenticationMode = ScramSha1Authentication,
    dbs: Map[String, DBConfig] = Map()
  )

  case class DBConfig(
    name: Option[String],
    collections: Map[String, CollectionConfig],
  )

  case class Credential(username: String, password: String)

  case class CollectionConfig(
    name: Option[String],
    readPreference: Option[ReadPreference]
  )

  implicit val readPreferenceValueReader: ValueReader[ReadPreference] = new ValueReader[ReadPreference] {
    def read(config: Config, path: String): ReadPreference = config.getString(path) match {
      case "secondary" => ReadPreference.secondary
      case "primary" =>  ReadPreference.primary
      case s => throw new MongoDBConfigurationException(s + " is not a recognized read preference")
    }
  }

  implicit val authenticationModeReader: ValueReader[AuthenticationMode] = new ValueReader[AuthenticationMode] {
    def read(config: Config, path: String): AuthenticationMode = config.getString(path) match {
      case "CR" => CrAuthentication
      case "SCRAM-SHA-1" =>  ScramSha1Authentication
      case s => throw new MongoDBConfigurationException(s + " is not a recognized Authentication Mode, Options are: CR, SCRAM-SHA-1")
    }
  }
}

