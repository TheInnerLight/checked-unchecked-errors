import shared._
import zio.IO

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

  sealed trait PostgresError
  object PostgresError {
    final case object SQLError
    final case object InvariantViolation
    final case object TimedOut
    final case object IOError
  }

  trait SendHttp {
    def send[A, B](http: Request[A]): IO[HttpError, Response[B]]
  }

  trait Postgres {
    def get[Id, Value](key : Id) : IO[PostgresError, Value]
  }

  // Attempt

  sealed trait GetProgramInfoError
  object GetProgramInfoError {
    final case class PostgresGetProgramInfoError(kafkaError: PostgresError) extends GetProgramInfoError
    final case class HttpGetProgramInfoError(httpError: HttpError) extends GetProgramInfoError
  }

  trait GetProgramInfo {
    def getProgramInfo(programId : ProgramId) : IO[GetProgramInfoError, ProgramInfo]
  }

  class GetProgramInfoHttp(http : SendHttp) extends GetProgramInfo {
    override def getProgramInfo(programId : ProgramId): IO[GetProgramInfoError, ProgramInfo] =
      http.send[ProgramId, ProgramInfo](Request(programId))
        .mapError(GetProgramInfoError.HttpGetProgramInfoError)
        .map(_.body)
  }

  class GetProgramInfoPostgres(postgres: Postgres) extends GetProgramInfo {
    override def getProgramInfo(programId : ProgramId): IO[GetProgramInfoError, ProgramInfo] =
      postgres.get[ProgramId, ProgramInfo](programId)
        .mapError(GetProgramInfoError.PostgresGetProgramInfoError)
  }

  // Now add caching

  // It would be nice to make this represent a general Cache but how do we enumerate all possible caching errors?  Let's just do redis

  sealed trait RedisError
  object RedisError {
    case object UnreachableCluster
    case object SocketError
    case object ExceededMaxClients
  }

  trait Redis {
    // Note that Option is an error case - the error case we usually care about, namely it not existing in the DB.  This could go in the ZIO error channel but the ergonomics would be worse.
    def get[Id, Value](key : Id) : IO[RedisError, Option[Value]]
    def put[Id, Value](key : Id, value : Value) : IO[RedisError, Unit]
  }

  def cached[Id, Value](redis: Redis)(f : Id => IO[E, Value]) : Id => IO[E + RedisError, Value] = id =>
    for {
      maybeVal <- redis.get(id)
      result <- maybeVal match {
        case Some(value) => IO.effectTotal(value)
        case None        => f(id)
      }
    } yield result

}
