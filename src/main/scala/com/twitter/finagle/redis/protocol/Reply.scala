package com.twitter.finagle.redis.protocol

import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.finagle.parser.incremental._
import com.twitter.finagle.parser.util.DecodingHelpers._


sealed abstract class Reply

object Reply {
  case class Status(message: ChannelBuffer) extends Reply
  case class Error(message: ChannelBuffer) extends Reply
  case class Integer(integer: Int) extends Reply
  case class Bulk(data: Option[ChannelBuffer]) extends Reply
  case class MultiBulk(data: Option[Seq[Bulk]]) extends Reply
}

object ReplyDecoder {
  import Reply._
  import Parsers._

  private val skipCRLF = skipBytes(2)

  private val readDecimalInt = readLine into { b => lift(decodeDecimalInt(b)) }

  private val readStatusReply = readLine map { Status(_) }

  private val readErrorReply = readLine map { Error(_) }

  private val readIntegerReply = readDecimalInt map { Integer(_) }

  private val readBulkReply = readDecimalInt into { size =>
    if (size < 0) {
      success(Bulk(None))
    } else {
      readBytes(size) into { b => skipCRLF ^^^ Bulk(Some(b)) }
    }
  }

  private val readBulkForMulti = accept("$") append readBulkReply

  private val readMultiBulkReply = readDecimalInt into { count =>
    if (count < 0) {
      success(MultiBulk(None))
    } else {
      repN(count, readBulkForMulti) map { bulks =>
        MultiBulk(Some(bulks))
      }
    }
  }

  val parser = choice(
    "+" -> readStatusReply,
    "-" -> readErrorReply,
    ":" -> readIntegerReply,
    "$" -> readBulkReply,
    "*" -> readMultiBulkReply
  )
}

class ReplyDecoder extends ParserDecoder[Reply](ReplyDecoder.parser)
