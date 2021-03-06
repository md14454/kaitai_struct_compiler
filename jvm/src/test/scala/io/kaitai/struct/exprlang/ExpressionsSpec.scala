package io.kaitai.struct.exprlang

import io.kaitai.struct.exprlang.Ast._
import io.kaitai.struct.exprlang.Ast.expr._
import io.kaitai.struct.exprlang.Ast.operator._
import io.kaitai.struct.exprlang.Ast.cmpop._
import io.kaitai.struct.exprlang.Ast.unaryop._
import org.scalatest.FunSpec
import org.scalatest.Matchers._

import scala.collection.mutable.ArrayBuffer

class ExpressionsSpec extends FunSpec {
  describe("Expressions.parse") {
    it("parses single positive integer") {
      Expressions.parse("123") should be (IntNum(123))
    }

    it("parses single negative integer") {
      Expressions.parse("-456") should be (UnaryOp(Minus, IntNum(456)))
    }

    it("parses positive integer with underscores") {
      Expressions.parse("100_500") should be (IntNum(100500))
    }

    it("parses hex integer") {
      Expressions.parse("0x1234") should be (IntNum(0x1234))
    }

    it("parses hex integer with underscores") {
      Expressions.parse("0x12_34") should be (IntNum(0x1234))
    }

    it("parses octal integer") {
      Expressions.parse("0o644") should be (IntNum(420))
    }

    it("parses octal integer with undescores") {
      Expressions.parse("0o06_44") should be (IntNum(420))
    }

    it("parses binary integer") {
      Expressions.parse("0b10101010") should be (IntNum(0xaa))
    }

    it("parses binary integer with undescores") {
      Expressions.parse("0b1010_1_010") should be (IntNum(0xaa))
    }

    it("parses 1 + 2") {
      Expressions.parse("1 + 2") should be (BinOp(IntNum(1), Add, IntNum(2)))
    }

    it("parses 1 + 2 + 5") {
      Expressions.parse("1 + 2 + 5") should be (
        BinOp(BinOp(IntNum(1), Add, IntNum(2)), Add, IntNum(5))
      )
    }

    it("parses (1 + 2) / (7 * 8)") {
      Expressions.parse("(1 + 2) / (7 * 8)") should be (
        BinOp(
          BinOp(IntNum(1), Add, IntNum(2)),
          Div,
          BinOp(IntNum(7), Mult, IntNum(8))
        )
      )
    }

    it("parses 1 < 2") {
      Expressions.parse("1 < 2") should be (Compare(IntNum(1), Lt, IntNum(2)))
    }

    it("parses a[42]") {
      Expressions.parse("a[42]") should be (Subscript(Name(identifier("a")), IntNum(42)))
    }

    it("parses a[42 - 2]") {
      Expressions.parse("a[42 - 2]") should be (
        Subscript(
          Name(identifier("a")),
          BinOp(IntNum(42), Sub, IntNum(2))
        )
      )
    }

    it("parses 2 < 3 ? \"foo\" : \"bar\"") {
      Expressions.parse("2 < 3 ? \"foo\" : \"bar\"") should be (
        IfExp(
          Compare(IntNum(2), Lt, IntNum(3)),
          Str("foo"),
          Str("bar")
        )
      )
    }

    it("parses bitwise invert operation") {
      Expressions.parse("~777") should be (UnaryOp(Invert, IntNum(777)))
    }

    it("parses ~(7+3)") {
      Expressions.parse("~(7+3)") should be (UnaryOp(Invert, BinOp(IntNum(7), Add, IntNum(3))))
    }

    it("parses port::http") {
      Expressions.parse("port::http") should be (EnumByLabel(identifier("port"), identifier("http")))
    }

    it("parses port::http.to_i + 8000 == 8080") {
      Expressions.parse("port::http.to_i + 8000 == 8080") should be (
        Compare(
          BinOp(
            Attribute(
              EnumByLabel(identifier("port"),identifier("http")),
              identifier("to_i")
            ),
            Add,
            IntNum(8000)
          ),
          Eq,
          IntNum(8080)
        )
      )
    }

    it("parses [1, 2, 0x1234]") {
      Expressions.parse("[1, 2, 0x1234]") should be (
        List(ArrayBuffer(IntNum(1), IntNum(2), IntNum(4660)))
      )
    }

    // Boolean literals
    it("parses true") {
      Expressions.parse("true") should be (Bool(true))
    }

    it("parses false") {
      Expressions.parse("false") should be (Bool(false))
    }

    it("parses truer") {
      Expressions.parse("truer") should be (Name(identifier("truer")))
    }

    // String literals
    it("parses simple string") {
      Expressions.parse("\"abc\"") should be (Str("abc"))
    }

    it("parses interpolated string with newline") {
      Expressions.parse("\"abc\\ndef\"") should be (Str("abc\ndef"))
    }

    it("parses non-interpolated string with newline") {
      Expressions.parse("'abc\\ndef'") should be (Str("abc\\ndef"))
    }

    it("parses interpolated string with zero char") {
      Expressions.parse("\"abc\\0def\"") should be (Str("abc\0def"))
    }

    it("parses non-interpolated string with zero char") {
      Expressions.parse("'abc\\0def'") should be (Str("abc\\0def"))
    }

    it("parses interpolated string with octal char") {
      Expressions.parse("\"abc\\75def\"") should be (Str("abc=def"))
    }

    it("parses interpolated string with hex unicode char") {
      Expressions.parse("\"abc\\u21bbdef\"") should be (Str("abc\u21bbdef"))
    }

    // Casts
    it("parses 123.as<u4>") {
      Expressions.parse("123.as<u4>") should be (CastToType(IntNum(123),identifier("u4")))
    }

    it("parses (123).as<u4>") {
      Expressions.parse("(123).as<u4>") should be (CastToType(IntNum(123),identifier("u4")))
    }

    it("parses \"str\".as<x>") {
      Expressions.parse("\"str\".as<x>") should be (CastToType(Str("str"),identifier("x")))
    }

    it("parses foo.as<x>") {
      Expressions.parse("foo.as<x>") should be (CastToType(Name(identifier("foo")),identifier("x")))
    }

    it("parses foo.as < x  >  ") {
      Expressions.parse("foo.as < x  >  ") should be (CastToType(Name(identifier("foo")),identifier("x")))
    }

    it("parses foo.as") {
      Expressions.parse("foo.as") should be (Attribute(Name(identifier("foo")),identifier("as")))
    }

    it("parses foo.as<x") {
      Expressions.parse("foo.as<x") should be (
        Compare(
          Attribute(Name(identifier("foo")),identifier("as")),
          Lt,
          Name(identifier("x"))
        )
      )
    }

    // Attribute / method call
    it("parses 123.to_s") {
      Expressions.parse("123.to_s") should be (Attribute(IntNum(123),identifier("to_s")))
    }

    it("parses 123.4.to_s") {
      Expressions.parse("123.4.to_s") should be (Attribute(FloatNum(123.4),identifier("to_s")))
    }
  }
}
