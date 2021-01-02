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

import cats.effect._
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._
import fs2.Stream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blazecore.{QueueTestHead, SeqTestHead}
import org.http4s.client.blaze.bits.DefaultUserAgent
import org.http4s.headers.`User-Agent`
import org.typelevel.ci.CIString
import scala.concurrent.duration._

class Http1ClientStageSuite extends Http4sSuite {
  val dispatcher = Dispatcher[IO].allocated.map(_._1).unsafeRunSync()

  val trampoline = org.http4s.blaze.util.Execution.trampoline

  val www_foo_test = Uri.uri("http://www.foo.test")
  val FooRequest = Request[IO](uri = www_foo_test)
  val FooRequestKey = RequestKey.fromRequest(FooRequest)

  val LongDuration = 30.seconds

  // Common throw away response
  val resp = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndone"

  private def mkConnection(key: RequestKey, userAgent: Option[`User-Agent`] = None) =
    new Http1Connection[IO](
      key,
      executionContext = trampoline,
      maxResponseLineSize = 4096,
      maxHeaderLength = 40960,
      maxChunkSize = Int.MaxValue,
      chunkBufferMaxSize = 1024,
      parserMode = ParserMode.Strict,
      userAgent = userAgent,
      dispatcher = dispatcher
    )

  private def mkBuffer(s: String): ByteBuffer =
    ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1))

  private def bracketResponse[T](req: Request[IO], resp: String)(
      f: Response[IO] => IO[T]): IO[T] = {
    val stage = mkConnection(FooRequestKey)
    IO.defer {
      val h = new SeqTestHead(resp.toSeq.map { chr =>
        val b = ByteBuffer.allocate(1)
        b.put(chr.toByte).flip()
        b
      })
      LeafBuilder(stage).base(h)

      for {
        resp <- stage.runRequest(req, IO.never)
        t <- f(resp)
        _ <- IO(stage.shutdown())
      } yield t
    }
  }

  private def getSubmission(
      req: Request[IO],
      resp: String,
      stage: Http1Connection[IO]): IO[(String, String)] =
    for {
      q <- Queue.unbounded[IO, Option[ByteBuffer]]
      h = new QueueTestHead(q)
      d <- Deferred[IO, Unit]
      _ <- IO(LeafBuilder(stage).base(h))
      _ <- (d.get >> Stream
        .emits(resp.toList)
        .map { c =>
          val b = ByteBuffer.allocate(1)
          b.put(c.toByte).flip()
          b
        }
        .noneTerminate
        .through(_.evalMap(q.offer))
        .compile
        .drain).start
      req0 = req.withBodyStream(req.body.onFinalizeWeak(d.complete(()).void))
      response <- stage.runRequest(req0, IO.never)
      result <- response.as[String]
      _ <- IO(h.stageShutdown())
      buff <- IO.fromFuture(IO(h.result))
      request = new String(buff.array(), StandardCharsets.ISO_8859_1)
    } yield (request, result)

  private def getSubmission(
      req: Request[IO],
      resp: String,
      userAgent: Option[`User-Agent`] = None): IO[(String, String)] = {
    val key = RequestKey.fromRequest(req)
    val tail = mkConnection(key, userAgent)
    getSubmission(req, resp, tail)
  }

  test("Run a basic request") {
    getSubmission(FooRequest, resp).map { case (request, response) =>
      val statusLine = request.split("\r\n").apply(0)
      assertEquals(statusLine, "GET / HTTP/1.1")
      assertEquals(response, "done")
    }
  }

  test("Submit a request line with a query") {
    val uri = "/huh?foo=bar"
    val Right(parsed) = Uri.fromString("http://www.foo.test" + uri)
    val req = Request[IO](uri = parsed)

    getSubmission(req, resp).map { case (request, response) =>
      val statusLine = request.split("\r\n").apply(0)
      assertEquals(statusLine, "GET " + uri + " HTTP/1.1")
      assertEquals(response, "done")
    }
  }

  test("Fail when attempting to get a second request with one in progress") {
    val tail = mkConnection(FooRequestKey)
    val (frag1, frag2) = resp.splitAt(resp.length - 1)
    val h = new SeqTestHead(List(mkBuffer(frag1), mkBuffer(frag2), mkBuffer(resp)))
    LeafBuilder(tail).base(h)

    try {
      tail.runRequest(FooRequest, IO.never).unsafeRunAsync {
        case Right(_) => (); case Left(_) => ()
      } // we remain in the body

      intercept[Http1Connection.InProgressException.type] {
        tail
          .runRequest(FooRequest, IO.never)
          .unsafeRunSync()
      }
    } finally tail.shutdown()
  }

  test("Reset correctly") {
    val tail = mkConnection(FooRequestKey)
    try {
      val h = new SeqTestHead(List(mkBuffer(resp), mkBuffer(resp)))
      LeafBuilder(tail).base(h)

      // execute the first request and run the body to reset the stage
      tail.runRequest(FooRequest, IO.never).unsafeRunSync().body.compile.drain.unsafeRunSync()

      val result = tail.runRequest(FooRequest, IO.never).unsafeRunSync()
      tail.shutdown()

      assertEquals(result.headers.size, 1)
    } finally tail.shutdown()
  }

  test("Alert the user if the body is to short") {
    val resp = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\ndone"
    val tail = mkConnection(FooRequestKey)

    try {
      val h = new SeqTestHead(List(mkBuffer(resp)))
      LeafBuilder(tail).base(h)

      val result = tail.runRequest(FooRequest, IO.never).unsafeRunSync()

      result.body.compile.drain.intercept[InvalidBodyException]
    } finally tail.shutdown()
  }

  test("Interpret a lack of length with a EOF as a valid message") {
    val resp = "HTTP/1.1 200 OK\r\n\r\ndone"

    getSubmission(FooRequest, resp).map(_._2).assertEquals("done")
  }

  test("Utilize a provided Host header") {
    val resp = "HTTP/1.1 200 OK\r\n\r\ndone"

    val req = FooRequest.withHeaders(headers.Host("bar.test"))

    getSubmission(req, resp).map { case (request, response) =>
      val requestLines = request.split("\r\n").toList
      assert(requestLines.contains("Host: bar.test"))
      assertEquals(response, "done")
    }
  }

  test("Insert a User-Agent header") {
    val resp = "HTTP/1.1 200 OK\r\n\r\ndone"

    getSubmission(FooRequest, resp, DefaultUserAgent).map { case (request, response) =>
      val requestLines = request.split("\r\n").toList
      assert(requestLines.contains(s"User-Agent: http4s-blaze/${BuildInfo.version}"))
      assertEquals(response, "done")
    }
  }

  test("Use User-Agent header provided in Request") {
    val resp = "HTTP/1.1 200 OK\r\n\r\ndone"

    val req = FooRequest.withHeaders(Header.Raw(CIString("User-Agent"), "myagent"))

    getSubmission(req, resp).map { case (request, response) =>
      val requestLines = request.split("\r\n").toList
      assert(requestLines.contains("User-Agent: myagent"))
      assertEquals(response, "done")
    }
  }

  test("Not add a User-Agent header when configured with None") {
    val resp = "HTTP/1.1 200 OK\r\n\r\ndone"
    val tail = mkConnection(FooRequestKey)

    try {
      val (request, response) = getSubmission(FooRequest, resp, tail).unsafeRunSync()
      tail.shutdown()

      val requestLines = request.split("\r\n").toList

      assertEquals(requestLines.find(_.startsWith("User-Agent")), None)
      assertEquals(response, "done")
    } finally tail.shutdown()
  }

  // TODO fs2 port - Currently is elevating the http version to 1.1 causing this test to fail
  test("Allow an HTTP/1.0 request without a Host header".ignore) {
    val resp = "HTTP/1.0 200 OK\r\n\r\ndone"

    val req = Request[IO](uri = www_foo_test, httpVersion = HttpVersion.`HTTP/1.0`)

    getSubmission(req, resp).map { case (request, response) =>
      assert(!request.contains("Host:"))
      assertEquals(response, "done")
    }
  }

  test("Support flushing the prelude") {
    val req = Request[IO](uri = www_foo_test, httpVersion = HttpVersion.`HTTP/1.0`)
    /*
     * We flush the prelude first to test connection liveness in pooled
     * scenarios before we consume the body.  Make sure we can handle
     * it.  Ensure that we still get a well-formed response.
     */
    getSubmission(req, resp).map(_._2).assertEquals("done")
  }

  test("Not expect body if request was a HEAD request") {
    val contentLength = 12345L
    val resp = s"HTTP/1.1 200 OK\r\nContent-Length: $contentLength\r\n\r\n"
    val headRequest = FooRequest.withMethod(Method.HEAD)
    val tail = mkConnection(FooRequestKey)
    try {
      val h = new SeqTestHead(List(mkBuffer(resp)))
      LeafBuilder(tail).base(h)

      val response = tail.runRequest(headRequest, IO.never).unsafeRunSync()
      assertEquals(response.contentLength, Some(contentLength))

      // connection reusable immediately after headers read
      assert(tail.isRecyclable)

      // body is empty due to it being HEAD request
      val length = response.body.compile.toVector
        .unsafeRunSync()
        .foldLeft(0L)((long, _) => long + 1L)

      assertEquals(length, 0L)
    } finally tail.shutdown()
  }

  {
    val resp = "HTTP/1.1 200 OK\r\n" +
      "Transfer-Encoding: chunked\r\n\r\n" +
      "3\r\n" +
      "foo\r\n" +
      "0\r\n" +
      "Foo:Bar\r\n" +
      "\r\n"

    val req = Request[IO](uri = www_foo_test, httpVersion = HttpVersion.`HTTP/1.1`)

    test("Support trailer headers") {
      val hs: IO[Headers] = bracketResponse(req, resp) { (response: Response[IO]) =>
        for {
          _ <- response.as[String]
          hs <- response.trailerHeaders
        } yield hs
      }

      hs.map(_.toList.mkString).assertEquals("Foo: Bar")
    }

    test("Fail to get trailers before they are complete") {
      val hs: IO[Headers] = bracketResponse(req, resp) { (response: Response[IO]) =>
        for {
          //body  <- response.as[String]
          hs <- response.trailerHeaders
        } yield hs
      }

      hs.intercept[IllegalStateException]
    }
  }
}