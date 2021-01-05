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
import cats.syntax.all._
import java.util.concurrent.TimeoutException
//import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.util.TickWheelExecutor
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
        Resource.eval(manager.borrow(RequestKey.fromRequest(req))).use { next =>
          next.connection
            .runRequest(req, F.never[TimeoutException])
            .map { r =>
              Resource.makeCase(F.pure(r)) {
                case (_, ExitCase.Succeeded) =>
                  manager.release(next.connection)
                case _ =>
                  println(idleTimeout)
                  println(responseHeaderTimeout)
                  println(ec)
                  println(requestTimeout)
                  println(scheduler)
                  manager.invalidate(next.connection)
              }
            }
        }
      }
    }
}
