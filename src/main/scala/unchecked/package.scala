import cats.{Applicative, Monad}
import shared._
import cats.implicits._

package object unchecked {
  // type class for sending http
  trait SendHttp[F[_]] {
    def send[A, B](request: Request[A]) : F[Response[B]]
  }

  object SendHttp {
    def apply[F[_]](implicit sendHttp: SendHttp[F]): SendHttp[F] = sendHttp
  }

  // type class for getting from postgres
  trait GetPostgres[F[_]] {
    def get[Id, Value](key : Id) : F[Value]
  }

  object GetPostgres {
    def apply[F[_]](implicit getPostgres: GetPostgres[F]): GetPostgres[F] = getPostgres
  }

  // type class for getting program info
  trait GetProgramInfo[F[_]] {
    def getProgramInfo(programId : ProgramId) : F[ProgramInfo]
  }

  // implementations for Http / Postgres
  object GetProgramInfoHttp {
    implicit def getProgramInfoHttp[F[_] : SendHttp : Applicative]: GetProgramInfo[F] = new GetProgramInfo[F] {
      override def getProgramInfo(programId : ProgramId): F[ProgramInfo] =
        SendHttp[F].send[ProgramId, ProgramInfo](Request(programId)).map(_.body)
    }
  }

  object GetProgramInfoPostgres {
    implicit def getProgramInfoPostgres[F[_] : GetPostgres : Applicative] : GetProgramInfo[F] = new GetProgramInfo[F] {
      override def getProgramInfo(programId: ProgramId): F[ProgramInfo] =
        GetPostgres[F].get[ProgramId, ProgramInfo](programId)
    }
  }

  // Now add caching

  trait Cache[F[_]] {
    // we still have the error case we actually care about - the record doesn't exist in the DB
    def get[Id, Value](key : Id) : F[Option[Value]]
    def put[Id, Value](key : Id, value : Value) : F[Unit]
  }

  object Cache {
    def apply[F[_]](implicit cache: Cache[F]): Cache[F] = cache
  }

  // Add a cache capability to anything :)
  def cached[F[_] : Cache : Monad, Id, Value](f : Id => F[Value]) : Id => F[Value] = id =>
    for {
      maybeVal <- Cache[F].get[Id, Value](id)
      result <- maybeVal match {
        case Some(value) => Monad[F].pure(value)
        case None        => f(id)
      }
    } yield result

}
