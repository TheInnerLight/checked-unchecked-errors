package object shared {

  case class Request[A](body : A)

  case class Response[A](body : A)

  case class ProgramId()

  case class ProgramInfo()

}
