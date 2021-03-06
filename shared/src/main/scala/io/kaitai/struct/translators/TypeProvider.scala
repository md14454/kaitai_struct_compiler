package io.kaitai.struct.translators

import io.kaitai.struct.datatype.DataType
import io.kaitai.struct.format.{ClassSpec, EnumSpec}

/**
  * Common interface of a "type provider", i.e. a class that answers
  * queries on resolving particular user types / attributes by given
  * name (which is encountered in an expression).
  */
trait TypeProvider {
  def nowClass: ClassSpec
  def determineType(attrName: String): DataType
  def determineType(inClass: ClassSpec, attrName: String): DataType
  def resolveEnum(enumName: String): EnumSpec
  def resolveType(typeName: String): DataType
}
