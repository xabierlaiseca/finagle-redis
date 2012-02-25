package com.twitter.finagle.parser.incremental

import scala.annotation.tailrec
import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.finagle.parser.util.ChainableTuple

object ParseState {
  val IsEmpty = 0.toByte
  val IsCont  = 1.toByte
  val IsRet   = 2.toByte
  val IsFail  = 3.toByte
  val IsError = 4.toByte
}

import ParseState._

final class ParseState {

  var _type: Byte        = IsEmpty
  var _parser: Parser[_] = _
  var _value: Any        = _
  var _msg: String       = _

  @inline def cont(p: Parser[_]) { _type = IsCont;  _parser = p }
  @inline def ret(r: Any)        { _type = IsRet;   _value  = r }
  @inline def fail(msg: String)  { _type = IsFail;  _msg    = msg }
  @inline def error(msg: String) { _type = IsError; _msg    = msg }

  @inline def isCont  = _type == IsCont
  @inline def isRet   = _type == IsRet
  @inline def isFail  = _type == IsFail
  @inline def isError = _type == IsError

  def value[T]      = _value.asInstanceOf[T]
  def nextParser[T] = _parser.asInstanceOf[Parser[T]]
  def errorMessage  = _msg

  def toResult[T]: ParseResult[T] = _type match {
    case IsRet   => Return(_value.asInstanceOf[T])
    case IsCont  => Continue(_parser.asInstanceOf[Parser[T]])
    case IsFail  => Fail(_msg)
    case IsError => Error(_msg)
    case IsEmpty => sys.error("empty state")
  }
}

abstract class Parser[+Out] {
  import Parsers._

  def decode(buffer: ChannelBuffer) = {
    val state = new ParseState

    decodeWithState(state, buffer)
    state.toResult[Out]
  }

  def decodeWithState(state: ParseState, buffer: ChannelBuffer)

  // basic composition

  def then[T](rhs: Parser[T]): Parser[T] = new ThenParser(this, rhs)

  def then[T](rv: T): Parser[T] = new ThenParser(this, success(rv))

  def through[T](rhs: Parser[T]): Parser[Out] = this flatMap { rhs then success(_) }

  def and[T, C <: ChainableTuple](rhs: Parser[T])(implicit chn: Out => C): Parser[C#Next[T]] = {
    for (tup <- this; next <- rhs) yield chn(tup).append(next)
  }

  def or[O >: Out](rhs: Parser[O]): Parser[O] = new OrParser(this, rhs)

  def flatMap[T](f: Out => Parser[T]): Parser[T] = new FlatMapParser(this, f)

  def map[T](f: Out => T): Parser[T] = this flatMap { o => success(f(o)) }


  // yay operators...this may be a bad idea.

  def * = rep(this)

  def + = rep1(this)

  def ? = opt(this)

  def <<[T](rhs: Parser[T]) = this through rhs

  def >>[T](rhs: Parser[T]) = this then rhs

  def >>=[T](f: Out => Parser[T]) = this flatMap f

  def ^[T](r: T) = this then r

  def ^^[T](f: Out => T) = this map f

  def |[T](rhs: Parser[T]) = this or rhs

  def &[T, C <: ChainableTuple](rhs: Parser[T])(implicit c: Out => C): Parser[C#Next[T]] = {
    this and rhs
  }

}


final class LiftParser[+Out](r: ParseResult[Out]) extends Parser[Out] {
  def decodeWithState(state: ParseState, buffer: ChannelBuffer) {
    r match {
      case Continue(next) => state.cont(next)
      case Return(ret)    => state.ret(ret)
      case Fail(msg)      => state.fail(msg)
      case Error(msg)     => state.error(msg)
    }
  }
}

final class FlatMapParser[T, +Out](parser: Parser[T], f: T => Parser[Out])
extends Parser[Out] {
  def decodeWithState(state: ParseState, buffer: ChannelBuffer) {
    parser.decodeWithState(state, buffer)

    if (state.isRet) {
      f(state.value).decodeWithState(state, buffer)
    } else if (state.isCont) {
      state.cont(state.nextParser flatMap f)
    }
  }
}

final class ThenParser[+Out](parser: Parser[_], next: Parser[Out])
extends Parser[Out] {
  def decodeWithState(state: ParseState, buffer: ChannelBuffer) {
    parser.decodeWithState(state, buffer)

    if (state.isRet) {
      next.decodeWithState(state, buffer)
    } else if (state.isCont) {
      state.cont(state.nextParser then next)
    }
  }

  override def then[T](other: Parser[T]): Parser[T] = {
    new ThenParser(parser, next then other)
  }
}


final class RepeatParser[+Out](
  parser: Parser[Out],
  count: Int,
  prevResult: Array[Any] = null,
  currParser: Parser[Out] = null
) extends Parser[Seq[Out]] {

  def decodeWithState(state: ParseState, buffer: ChannelBuffer) {
    var left   = count
    var result = if (prevResult eq null) new Array[Any](left) else prevResult
    val p      = if (currParser eq null) parser else currParser
    val total  = result.size

    do {
      p.decodeWithState(state, buffer)

      if (state.isRet) {
        result(total - left) = state.value[Any]
        left -= 1
      } else if (state.isCont) {
        state.cont(new RepeatParser(parser, left, result, currParser))
        return
      }
    } while (left > 0)

    state.ret(result.toSeq)
  }
}

final class OrParser[+Out](choice: Parser[Out], tail: Parser[Out], committed: Boolean)
extends Parser[Out] {

  def this(p: Parser[Out], t: Parser[Out]) = this(p, t, false)

  def decodeWithState(state: ParseState, buffer: ChannelBuffer) {
    val start  = buffer.readerIndex
    choice.decodeWithState(state, buffer)
    val newCommitted = committed || buffer.readerIndex > start

    //result.or(tail, newCommitted)

    if (state.isCont) {
      state.cont(new OrParser(state.nextParser, tail, newCommitted))
    } else if (state.isFail) {
      if (newCommitted) {
        state.error(state.errorMessage)
      } else {
        tail.decodeWithState(state, buffer)
      }
    }
  }

  override def or[O >: Out](other: Parser[O]): Parser[O] = {
    new OrParser(choice, tail or other)
  }
}

final class NotParser(parser: Parser[_]) extends Parser[Unit] {

  def decodeWithState(state: ParseState, buffer: ChannelBuffer) = {
    val start     = buffer.readerIndex
    parser.decodeWithState(state, buffer)
    val committed = buffer.readerIndex > start

    //result.negate(committed)
    if (state.isCont) {
      if (committed) {
        state.error("Expected "+ parser +" to fail, but already consumed data.")
      } else {
        state.cont(new NotParser(state.nextParser))
      }
    } else if (state.isRet) {
      if (committed) {
        state.error("Expected "+ parser +" to fail, but already consumed data.")
      } else {
        state.fail("Expected "+ parser +" to fail.")
      }
    } else if (state.isFail) {
      if (committed) {
        state.error(state.errorMessage)
      } else {
        state.ret(())
      }
    }
  }
}
