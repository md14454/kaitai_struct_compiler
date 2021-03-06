package io.kaitai.struct

import io.kaitai.struct.datatype.DataType
import io.kaitai.struct.datatype.DataType._
import io.kaitai.struct.format._
import io.kaitai.struct.languages._
import io.kaitai.struct.languages.components.{LanguageCompiler, LanguageCompilerStatic}

import scala.collection.mutable.ListBuffer

class ClassCompiler(
  val topClass: ClassSpec,
  config: RuntimeConfig,
  langObj: LanguageCompilerStatic,
  outs: List[LanguageOutputWriter]
) extends AbstractCompiler {
  val provider = new ClassTypeProvider(topClass)
  val topClassName = topClass.name
  val lang: LanguageCompiler = langObj.getCompiler(provider, config, outs)

  override def compile {
    lang.fileHeader(topClassName.head)
    compileOpaqueClasses(topClass)
    compileClass(topClass)
    lang.fileFooter(topClassName.head)
    lang.close
  }

  def compileOpaqueClasses(topClass: ClassSpec) = {
    TypeProcessor.getOpaqueClasses(topClass).foreach((classSpec) =>
      if (classSpec != topClass)
        lang.opaqueClassDeclaration(classSpec)
    )
  }

  def compileClass(curClass: ClassSpec): Unit = {
    provider.nowClass = curClass

    curClass.doc.foreach((doc) => lang.classDoc(curClass.name, doc))
    lang.classHeader(curClass.name)

    val extraAttrs = ListBuffer[AttrSpec]()
    extraAttrs += AttrSpec(List(), RootIdentifier, UserTypeInstream(topClassName, None))
    extraAttrs += AttrSpec(List(), ParentIdentifier, UserTypeInstream(curClass.parentTypeName, None))

    // Forward declarations for recursive types
    curClass.types.foreach { case (typeName, _) => lang.classForwardDeclaration(List(typeName)) }

    if (lang.innerEnums)
      compileEnums(curClass)

    if (lang.debug)
      lang.debugClassSequence(curClass.seq)

    lang.classConstructorHeader(curClass.name, curClass.parentTypeName, topClassName)
    curClass.instances.foreach { case (instName, _) => lang.instanceClear(instName) }
    compileSeq(curClass.seq, extraAttrs)
    lang.classConstructorFooter

    lang.classDestructorHeader(curClass.name, curClass.parentTypeName, topClassName)
    curClass.seq.foreach((attr) => lang.attrDestructor(attr, attr.id))
    curClass.instances.foreach { case (id, instSpec) =>
      instSpec match {
        case pis: ParseInstanceSpec => lang.attrDestructor(pis, id)
        case _: ValueInstanceSpec => // ignore for now
      }
    }
    lang.classDestructorFooter

    // Recursive types
    if (lang.innerClasses) {
      compileSubclasses(curClass)

      provider.nowClass = curClass
    }

    curClass.instances.foreach { case (instName, instSpec) => compileInstance(curClass.name, instName, instSpec, extraAttrs) }

    // Attributes declarations and readers
    (curClass.seq ++ extraAttrs).foreach((attr) => lang.attributeDeclaration(attr.id, attr.dataTypeComposite, attr.cond))
    (curClass.seq ++ extraAttrs).foreach { (attr) =>
      attr.doc.foreach((doc) => lang.attributeDoc(attr.id, doc))
      lang.attributeReader(attr.id, attr.dataTypeComposite, attr.cond)
    }

    lang.classFooter(curClass.name)

    if (!lang.innerClasses)
      compileSubclasses(curClass)

    if (!lang.innerEnums)
      compileEnums(curClass)
  }

  def compileSeq(seq: List[AttrSpec], extraAttrs: ListBuffer[AttrSpec]) = {
    var wasUnaligned = false
    seq.foreach { (attr) =>
      val nowUnaligned = isUnalignedBits(attr.dataType)
      if (wasUnaligned && !nowUnaligned)
        lang.alignToByte(lang.normalIO)
      lang.attrParse(attr, attr.id, extraAttrs)
      wasUnaligned = nowUnaligned
    }
  }

  def compileEnums(curClass: ClassSpec): Unit =
    curClass.enums.foreach { case(_, enumColl) => compileEnum(curClass, enumColl) }

  def compileSubclasses(curClass: ClassSpec): Unit =
    curClass.types.foreach { case (_, intClass) => compileClass(intClass) }

  def compileInstance(className: List[String], instName: InstanceIdentifier, instSpec: InstanceSpec, extraAttrs: ListBuffer[AttrSpec]): Unit = {
    // Determine datatype
    val dataType = TypeProcessor.getInstanceDataType(instSpec)

    // Declare caching variable
    val condSpec = instSpec match {
      case vis: ValueInstanceSpec => ConditionalSpec(vis.ifExpr, NoRepeat)
      case pis: ParseInstanceSpec => pis.cond
    }
    lang.instanceDeclaration(instName, dataType, condSpec)

    instSpec.doc.foreach((doc) => lang.attributeDoc(instName, doc))
    lang.instanceHeader(className, instName, dataType)
    lang.instanceCheckCacheAndReturn(instName)

    instSpec match {
      case vi: ValueInstanceSpec =>
        lang.attrParseIfHeader(instName, vi.ifExpr)
        lang.instanceCalculate(instName, dataType, vi.value)
        lang.attrParseIfFooter(vi.ifExpr)
      case i: ParseInstanceSpec =>
        lang.attrParse(i, instName, extraAttrs)
    }

    lang.instanceSetCalculated(instName)
    lang.instanceReturn(instName)
    lang.instanceFooter
  }

  def compileEnum(curClass: ClassSpec, enumColl: EnumSpec): Unit = {
    lang.enumDeclaration(curClass.name, enumColl.name.last, enumColl.sortedSeq)
  }

  def isUnalignedBits(dt: DataType): Boolean =
    dt match {
      case _: BitsType | BitsType1 => true
      case et: EnumType => isUnalignedBits(et.basedOn)
      case _ => false
    }
}

object ClassCompiler {
  def fromClassSpecToString(topClass: ClassSpec, lang: LanguageCompilerStatic, conf: RuntimeConfig):
    Map[String, String] = {
    val config = updateConfig(conf, topClass)
    lang match {
      case GraphvizClassCompiler =>
        val out = new StringLanguageOutputWriter(lang.indent)
        val cc = new GraphvizClassCompiler(topClass, out)
        cc.compile
        Map(lang.outFileName(topClass.name.head) -> out.result)
      case CppCompiler =>
        val outSrc = new StringLanguageOutputWriter(lang.indent)
        val outHdr = new StringLanguageOutputWriter(lang.indent)
        val cc = new ClassCompiler(topClass, config, lang, List(outSrc, outHdr))
        cc.compile
        Map(
          s"${topClass.name.head}.h" -> outHdr.result,
          s"${topClass.name.head}.cpp" -> outSrc.result
        )
      case _ =>
        val out = new StringLanguageOutputWriter(lang.indent)
        val cc = new ClassCompiler(topClass, config, lang, List(out))
        cc.compile
        Map(lang.outFileName(topClass.name.head) -> out.result)
    }
  }

  /**
    * Updates runtime configuration with "enforcement" options that came from a source file itself.
    * Currently only used to enforce debug when "ks-debug: true" is specified in top-level "meta" key.
    * @param config original runtime configuration
    * @param topClass top-level class spec
    * @return updated runtime configuration with applied enforcements
    */
  def updateConfig(config: RuntimeConfig, topClass: ClassSpec): RuntimeConfig = {
    if (topClass.meta.get.forceDebug) {
      config.copy(debug = true)
    } else {
      config
    }
  }
}
