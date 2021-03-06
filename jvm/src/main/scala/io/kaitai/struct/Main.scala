package io.kaitai.struct

import java.io.File

import io.kaitai.struct.format.{ClassSpec, ClassSpecs, KSVersion, YAMLParseException}
import io.kaitai.struct.formats.JavaKSYParser
import io.kaitai.struct.languages.CppCompiler
import io.kaitai.struct.languages.components.LanguageCompilerStatic

object Main {
  KSVersion.current = BuildInfo.version

  case class CLIConfig(
    verbose: Seq[String] = Seq(),
    srcFiles: Seq[File] = Seq(),
    outDir: File = new File("."),
    targets: Seq[String] = Seq(),
    throwExceptions: Boolean = false,
    importPaths: Seq[String] = Seq(),
    runtime: RuntimeConfig = RuntimeConfig()
  )

  val ALL_LANGS = LanguageCompilerStatic.NAME_TO_CLASS.keySet
  val VALID_LANGS = ALL_LANGS + "all"

  def parseCommandLine(args: Array[String]): Option[CLIConfig] = {
    val parser = new scopt.OptionParser[CLIConfig](BuildInfo.name) {
      override def showUsageOnError = true

      head(BuildInfo.name, BuildInfo.version)

      arg[File]("<file>...") unbounded() action { (x, c) =>
        c.copy(srcFiles = c.srcFiles :+ x) } text("source files (.ksy)")

      //      opt[File]('o', "outfile") valueName("<file>") action { (x, c) =>
      //        c.copy(outDir = x)
      //      } text("output filename (only if processing 1 file)")

      opt[String]('t', "target") required() unbounded() valueName("<language>") action { (x, c) =>
        // TODO: make support for something like "-t java,python"
        if (x == "all") {
          c.copy(targets = ALL_LANGS.toSeq)
        } else {
          c.copy(targets = c.targets :+ x)
        }
      } text(s"target languages (${VALID_LANGS.mkString(", ")})") validate { x =>
        if (VALID_LANGS.contains(x)) {
          success
        } else {
          failure(s"'${x}' is not a valid target language; valid ones are: ${VALID_LANGS.mkString(", ")}")
        }
      }

      opt[File]('d', "outdir") valueName("<directory>") action { (x, c) =>
        c.copy(outDir = x)
      } text("output directory (filenames will be auto-generated)")

      val importPathExample = List("<directory>", "<directory>", "...").mkString(File.pathSeparator)
      opt[String]('I', "import-path") valueName(importPathExample) action { (x, c) =>
        c.copy(importPaths = c.importPaths ++ x.split(File.pathSeparatorChar))
      } text(".ksy library search path(s) for imports (see also KSPATH env variable)")

      opt[String]("java-package") valueName("<package>") action { (x, c) =>
        c.copy(runtime = c.runtime.copy(javaPackage = x))
      } text("Java package (Java only, default: root package)")

      opt[String]("dotnet-namespace") valueName("<namespace>") action { (x, c) =>
        c.copy(runtime = c.runtime.copy(dotNetNamespace = x))
      } text(".NET Namespace (.NET only, default: Kaitai)")

      opt[String]("php-namespace") valueName("<namespace>") action { (x, c) =>
        c.copy(runtime = c.runtime.copy(phpNamespace = x))
      } text("PHP Namespace (PHP only, default: root package)")

      opt[Boolean]("opaque-types") action { (x, c) =>
        c.copy(runtime = c.runtime.copy(opaqueTypes = x))
      } text("opaque types allowed, default: false")

      opt[Unit]("ksc-exceptions") action { (x, c) =>
        c.copy(throwExceptions = true)
      } text("ksc throws exceptions instead of human-readable error messages")

      opt[String]("verbose") action { (x, c) =>
        // TODO: make support for something like "--verbose file,parent"
        if (x == "all") {
          c.copy(verbose = Log.VALID_SUBSYS)
        } else {
          c.copy(verbose = c.verbose :+ x)
        }
      } text("verbose output") validate { x =>
        if (x == "all" || Log.VALID_SUBSYS.contains(x)) {
          success
        } else {
          failure(s"'$x' is not a valid verbosity flag; valid ones are: ${Log.VALID_SUBSYS.mkString(", ")}")
        }
      }

      opt[Unit]("debug") action { (x, c) =>
        c.copy(runtime = c.runtime.copy(debug = true))
      } text("enable debugging helpers (mostly used by visualization tools)")
      help("help") text("display this help and exit")
      version("version") text("output version information and exit")
    }

    parser.parse(args, CLIConfig())
  }

  def compileOne(srcFile: String, lang: String, outDir: String, config: CLIConfig): Unit = {
    Log.fileOps.info(() => s"compiling $srcFile for $lang...")

    val specs = JavaKSYParser.localFileToSpecs(srcFile, config)
    compileOne(specs, lang, outDir, config.runtime)
  }

  def compileOne(specs: ClassSpecs, langStr: String, outDir: String, config: RuntimeConfig): Unit = {
    val lang = LanguageCompilerStatic.byString(langStr)
    specs.foreach { case (_, classSpec) =>
      fromClassSpecToFile(classSpec, lang, outDir, config).compile
    }
  }

  def compileAll(srcFile: String, config: CLIConfig): Unit = {
    val specs = JavaKSYParser.localFileToSpecs(srcFile, config)

    config.targets.foreach { lang =>
      try {
        Log.fileOps.info(() => s"... compiling it for $lang... ")
        compileOne(specs, lang, s"${config.outDir}/$lang", config.runtime)
      } catch {
        case e: Exception =>
          e.printStackTrace()
        case e: Error =>
          e.printStackTrace()
      }
    }
  }

  def fromClassSpecToFile(topClass: ClassSpec, lang: LanguageCompilerStatic, outDir: String, conf: RuntimeConfig): AbstractCompiler = {
    val config = ClassCompiler.updateConfig(conf, topClass)
    val outPath = lang.outFilePath(config, outDir, topClass.name.head)
    Log.fileOps.info(() => s"... => $outPath")
    lang match {
      case GraphvizClassCompiler =>
        val out = new FileLanguageOutputWriter(outPath, lang.indent)
        new GraphvizClassCompiler(topClass, out)
      case CppCompiler =>
        val outSrc = new FileLanguageOutputWriter(s"$outPath.cpp", lang.indent)
        val outHdr = new FileLanguageOutputWriter(s"$outPath.h", lang.indent)
        new ClassCompiler(topClass, config, lang, List(outSrc, outHdr))
      case _ =>
        val out = new FileLanguageOutputWriter(outPath, lang.indent)
        new ClassCompiler(topClass, config, lang, List(out))
    }
  }

  private def envPaths: List[String] =
    sys.env.get("KSPATH").toList.flatMap((x) => x.split(File.pathSeparatorChar))

  def main(args: Array[String]): Unit = {
    parseCommandLine(args)  match {
      case None => System.exit(1)
      case Some(config0) =>
        val config = config0.copy(importPaths = config0.importPaths ++ envPaths)
        Log.initFromVerboseFlag(config.verbose)
        config.srcFiles.foreach { srcFile =>
          try {
            config.targets match {
              case Seq(lang) =>
                // single target, just use target directory as is
                compileOne(srcFile.toString, lang, config.outDir.toString, config)
              case _ =>
                // multiple targets, use additional directories
                compileAll(srcFile.toString, config)
            }
          } catch {
            case e: YAMLParseException =>
              if (config.throwExceptions) {
                throw e
              } else {
                Console.println(e.getMessage)
              }
          }
        }
    }
  }
}
