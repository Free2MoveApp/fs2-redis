/*
 * Copyright 2018 Fs2 Redis
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

package com.github.gvolpe.fs2redis.interpreter.streams

import cats.effect.{ Concurrent, Sync }
import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.Streaming
import com.github.gvolpe.fs2redis.connection.Fs2RedisMasterSlave
import com.github.gvolpe.fs2redis.domain._
import com.github.gvolpe.fs2redis.effect.{ JRFuture, Log }
import com.github.gvolpe.fs2redis.streams._
import fs2.Stream
import io.lettuce.core.{ ReadFrom, RedisURI }

object Fs2Streaming {

  def mkStreamingConnection[F[_]: Concurrent: Log, K, V](
      client: Fs2RedisClient,
      codec: Fs2RedisCodec[K, V],
      uri: RedisURI
  ): Stream[F, Streaming[Stream[F, ?], K, V]] = {
    val acquire = JRFuture
      .fromConnectionFuture {
        Sync[F].delay(client.underlying.connectAsync[K, V](codec.underlying, uri))
      }
      .map(c => new Fs2RawStreaming(c))

    val release: Fs2RawStreaming[F, K, V] => F[Unit] = c =>
      JRFuture.fromCompletableFuture(Sync[F].delay(c.client.closeAsync())) *>
        Log[F].info(s"Releasing Streaming connection: $uri")

    Stream.bracket(acquire)(release).map(rs => new Fs2Streaming(rs))
  }

  def mkMasterSlaveConnection[F[_]: Concurrent: Log, K, V](codec: Fs2RedisCodec[K, V], uris: RedisURI*)(
      readFrom: Option[ReadFrom] = None
  ): Stream[F, Streaming[Stream[F, ?], K, V]] =
    Stream.resource(Fs2RedisMasterSlave[F, K, V](codec, uris: _*)(readFrom)).map { conn =>
      new Fs2Streaming(new Fs2RawStreaming(conn.underlying))
    }

}

class Fs2Streaming[F[_]: Concurrent, K, V](rawStreaming: Fs2RawStreaming[F, K, V])
    extends Streaming[Stream[F, ?], K, V] {

  private[streams] val nextOffset: K => StreamingMessageWithId[K, V] => StreamingOffset[K] =
    key => msg => StreamingOffset.Custom(key, (msg.id.value.dropRight(2).toLong + 1).toString)

  private[streams] val offsetsByKey: List[StreamingMessageWithId[K, V]] => Map[K, Option[StreamingOffset[K]]] =
    list => list.groupBy(_.key).map { case (k, values) => k -> values.lastOption.map(nextOffset(k)) }

  override def append: Stream[F, StreamingMessage[K, V]] => Stream[F, Unit] =
    _.evalMap(msg => rawStreaming.xAdd(msg.key, msg.body).void)

  override def read(keys: Set[K], initialOffset: K => StreamingOffset[K]): Stream[F, StreamingMessageWithId[K, V]] = {
    val initial = keys.map(k => k -> initialOffset(k)).toMap
    Stream.eval(Ref.of[F, Map[K, StreamingOffset[K]]](initial)).flatMap { ref =>
      (for {
        offsets <- Stream.eval(ref.get)
        list <- Stream.eval(rawStreaming.xRead(offsets.values.toSet))
        newOffsets = offsetsByKey(list).collect { case (key, Some(value)) => key -> value }.toList
        _ <- Stream.eval(newOffsets.map { case (k, v) => ref.update(_.updated(k, v)) }.sequence)
        result <- Stream.fromIterator(list.iterator)
      } yield result).repeat
    }
  }

}
