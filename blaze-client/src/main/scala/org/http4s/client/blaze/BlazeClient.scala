/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package client
package blaze

import cats.effect.kernel.{Async, Resource}
import cats.effect.implicits._
import cats.syntax.all._
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
//import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.util.TickWheelExecutor
import org.http4s.blazecore.ResponseHeaderTimeoutStage
//import org.log4s.getLogger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** Blaze client implementation */
object BlazeClient {
  import Resource.ExitCase

//  private[this] val logger = getLogger

  private[blaze] def makeClient[F[_], A <: BlazeConnection[F]](
      manager: ConnectionManager[F, A],
      responseHeaderTimeout: Duration,
      idleTimeout: Duration,
      requestTimeout: Duration,
      scheduler: TickWheelExecutor,
      ec: ExecutionContext
  )(implicit F: Async[F]) =
    Client[F] { req =>
      Resource.suspend {
        val key = RequestKey.fromRequest(req)

        def borrow: Resource[F, manager.NextConnection] =
          Resource.eval(manager.borrow(key))

        def loop: F[Resource[F, Response[F]]] =
          borrow.use { next =>
            val res = next.connection
              .runRequest(req, F.never[TimeoutException])
              .map { r =>
                Resource.makeCase(F.pure(r)) {
                  case (_, ExitCase.Succeeded) =>
                    F.delay(manager.release(next.connection)).void
                  case _ =>
                    println(s"idle timeout: $idleTimeout")
                    F.delay(manager.invalidate(next.connection)).void
                }
              }

            responseHeaderTimeout match {
              case responseHeaderTimeout: FiniteDuration =>
                F.deferred[Unit].flatMap { gate =>
                  val responseHeaderTimeoutF: F[TimeoutException] =
                    F.delay {
                      val stage =
                        new ResponseHeaderTimeoutStage[ByteBuffer](
                          responseHeaderTimeout,
                          scheduler,
                          ec)
                      next.connection.spliceBefore(stage)
                      stage
                    }.bracket { stage =>
                      F.async[TimeoutException] { cb =>
                        F.delay(stage.init(cb)) >> gate.complete(()).as(None)
                      }
                    }(stage => F.delay(stage.removeStage()))

                  F.race(gate.get *> res, responseHeaderTimeoutF)
                    .flatMap[Resource[F, Response[F]]] {
                      case Left(r) => F.pure(r)
                      case Right(t) => F.raiseError(t)
                    }
                }
              case _ => res
            }
          }

        val res = loop
        requestTimeout match {
          case d: FiniteDuration =>
            F.race(
              res,
              F.async[TimeoutException] { cb =>
                F.delay {
                  scheduler.schedule(
                    new Runnable {
                      def run() =
                        cb(Right(new TimeoutException(
                          s"Request to $key timed out after ${d.toMillis} ms")))
                    },
                    ec,
                    d
                  )
                }.map(c => Some(F.delay(c.cancel())))
              }
            ).flatMap[Resource[F, Response[F]]] {
              case Left(r) => F.pure(r)
              case Right(t) => F.raiseError(t)
            }
          case _ =>
            res
        }
      }
    }
}
