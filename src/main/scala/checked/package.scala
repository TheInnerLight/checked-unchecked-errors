import shared._
import zio._

package object checked {
  sealed trait HttpError
  object HttpError {
    case object DnsLookupFailed extends HttpError
    case object TimedOut extends HttpError
    final case class ClientError(code : Int) extends HttpError
    final case class ServerError(code : Int) extends HttpError
    case object NoResponse extends HttpError
    case object IncompleteResponse extends HttpError
  }

  // Sending Http

  type SendHttp = Has[SendHttp.Service]

  object SendHttp {
    trait Service {
      def send[A, B](req: Request[A]): IO[HttpError, Response[B]]
    }

    def sendHttp[A, B](req: Request[A]): ZIO[SendHttp, HttpError, Response[B]] =
      ZIO.accessM(_.get.send(req))
  }

  // Postgres

  sealed trait PostgresError
  object PostgresError {
    final case object SQLError extends PostgresError
    final case object InvariantViolation extends PostgresError
    final case object TimedOut extends PostgresError
    final case object IOError extends PostgresError
  }

  type Postgres = Has[Postgres.Service]

  object Postgres {
    trait Service {
      def get[Id, Value](key: Id): IO[PostgresError, Value]
    }

    def get[Id, Value](key: Id): ZIO[Postgres, PostgresError, Value] =
      ZIO.accessM(_.get.get(key))
  }

  // Implement GetProgramInfo which can be done either via Postgres or via HTTP

  sealed trait GetProgramInfoError
  object GetProgramInfoError {
    final case class PostgresGetProgramInfoError(kafkaError: PostgresError) extends GetProgramInfoError
    final case class HttpGetProgramInfoError(httpError: HttpError) extends GetProgramInfoError
  }

  type GetProgramInfo = Has[GetProgramInfo.Service]

  object GetProgramInfo {
    trait Service {
      def getProgramInfo(programId : ProgramId) : IO[GetProgramInfoError, ProgramInfo]
    }

    val http : ZLayer[SendHttp, GetProgramInfoError, GetProgramInfo] = ZLayer.fromFunction(http =>
      new Service {
        override def getProgramInfo(programId: ProgramId): IO[GetProgramInfoError, ProgramInfo] =
          http.get
            .send[ProgramId, ProgramInfo](Request(programId))
            .mapError(GetProgramInfoError.HttpGetProgramInfoError)
            .map(_.body)
      }
    )

    val postgres : ZLayer[Postgres, GetProgramInfoError, GetProgramInfo] = ZLayer.fromFunction(postgres =>
      new Service {
        override def getProgramInfo(programId: ProgramId): IO[GetProgramInfoError, ProgramInfo] =
          postgres.get
            .get[ProgramId, ProgramInfo](programId)
            .mapError(GetProgramInfoError.PostgresGetProgramInfoError)
      }
    )

    // The error type exposes information about both http and postgres implementations even though our implementation can only ever use one of them.
    // Also a problem if we e.g. have a library with some ZIO services and we want to add a new implementation, can't just go and edit error ADT
    def getProgramInfo(programId : ProgramId) : ZIO[GetProgramInfo, GetProgramInfoError, ProgramInfo] =
      ZIO.accessM(_.get.getProgramInfo(programId))
  }

  // Now add caching
  // It would be nice to make this represent a general Cache but redis and e.g. an in memory cache would have very different errors.
  // Let's just do redis

  sealed trait RedisError
  object RedisError {
    case object UnreachableCluster extends RedisError
    case object SocketError extends RedisError
    case object ExceededMaxClients extends RedisError
  }

  type Redis = Has[Redis.Service]

  object Redis {
    trait Service {
      // Note that Option is an error case - the error case we usually care about, namely it not existing in the DB.  This could go in the ZIO error
      // channel but the ergonomics would be worse.
      def get[Id, Value](key : Id) : IO[RedisError, Option[Value]]
      def put[Id, Value](key : Id, value : Value) : IO[RedisError, Unit]
    }

    def get[Id, Value](key : Id) : ZIO[Redis, RedisError, Option[Value]] =
      ZIO.accessM(_.get.get(key))

    def put[Id, Value](key : Id, value : Value) : ZIO[Redis, RedisError, Unit] =
      ZIO.accessM(_.get.put(key, value))
  }

  // "enriching" functionality works easily for the dependency because we go from `E` to `E with Redis` but combining the error types
  // is now hard because the error types aren't compatible by default, this is because the original function has some known error,
  // we could use `Either` to store the RedisError and some arbitrary other type of error.
  def cached[Id, Value, OtherError, E](f : Id => ZIO[E, OtherError, Value]) : Id => ZIO[E with Redis, Either[RedisError, OtherError], Value] = id =>
    for {
      maybeVal <- Redis.get(id).mapError[Either[RedisError, OtherError]](Left(_))
      result <- maybeVal match {
        case Some(value) => IO.effectTotal(value)
        case None        => f(id).mapError[Either[RedisError, OtherError]](Right(_))
      }
    } yield result

  // logic could extract specific cases and implement some retrying logic
  def someLogic: ZIO[GetProgramInfo with Redis, Either[RedisError, GetProgramInfoError], ProgramInfo] =
    for {
      info <- cached(GetProgramInfo.getProgramInfo)(ProgramId("1234"))
        .retryWhile {
          case Left(RedisError.UnreachableCluster)                                            => true
          case Right(GetProgramInfoError.PostgresGetProgramInfoError(PostgresError.TimedOut)) => true
          case Right(GetProgramInfoError.HttpGetProgramInfoError(HttpError.DnsLookupFailed))  => true
          case Right(GetProgramInfoError.HttpGetProgramInfoError(HttpError.TimedOut))         => true
          case _                                                                              => true
        }
      // ...
      // ...
    } yield info

}
