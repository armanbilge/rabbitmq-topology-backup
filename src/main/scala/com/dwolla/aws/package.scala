package com.dwolla.aws

import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException}
import cats.effect._
import cats.implicits._
import fs2._
import org.reactivestreams.Publisher
import AwsEval._
import fs2.interop.reactivestreams.fromPublisher

object AwsEval extends AwsEval {
  private[aws] def cfToF[F[_]] = new PartialCompletableFutureToF[F]
}

trait AwsEval {
  def unfold[F[_]] = new PartiallyAppliedFromPublisherF[F]

  def eval[F[_]] = new PartiallyAppliedEvalF[F]
}

class PartiallyAppliedFromPublisherF[F[_]] {
  def apply[Res](publisher: Publisher[Res]) =
    new PartiallyAppliedFromPublisherFRes[F, Res](publisher)
}

class PartiallyAppliedFromPublisherFRes[F[_], Res](publisher: Publisher[Res]) {
  import scala.jdk.CollectionConverters._

  private def toStream[T](res: Res => java.lang.Iterable[T]): Res => Stream[F, T] =
    res andThen (_.asScala) andThen Chunk.iterable andThen Stream.chunk

  def apply[O](extractor: Res => java.lang.Iterable[O])
              (implicit ev: Async[F]): Stream[F, O] =
    fromPublisher[F, Res](publisher, 1)
      .flatMap(toStream(extractor))

}

class PartiallyAppliedEvalF[F[_]] {
  def apply[Req, Res, O](req: => Req)
                        (client: Req => CompletableFuture[Res])
                        (extractor: Res => O)
                        (implicit ev: Async[F]): F[O] =
    cfToF[F](client(req)).map(extractor)
}

private[aws] class PartialCompletableFutureToF[F[_]] {
  def apply[A](makeCf: => CompletableFuture[A])
              (implicit ev: Async[F]): F[A] =
    Async[F].async[A] { cb =>
      val cf = makeCf
      cf.handle[Unit]((result, err) => err match {
        case null =>
          cb(Right(result))
        case _: CancellationException =>
          ()
        case ex: CompletionException if ex.getCause ne null =>
          cb(Left(ex.getCause))
        case ex =>
          cb(Left(ex))
      })

      Sync[F].delay(cf.cancel(true)).void.some.pure[F]
    }
}
