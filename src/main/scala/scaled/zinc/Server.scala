//
// Scaled Scala Compiler - a front-end for Zinc used by Scaled's Scala support
// http://github.com/scaled/scala-compiler/blob/master/LICENSE

package scaled.zinc

import com.typesafe.zinc._
import java.io.File
import java.util.{Map => JMap}
import sbt.compiler.CompileFailed
import scala.collection.mutable.ArrayBuffer
import scaled.prococol.Sender
import xsbti.compile.CompileOrder

class Server (sender :Sender) {
  import scala.collection.convert.WrapAsScala._
  import scala.collection.convert.WrapAsJava._

  val defScalacVersion = "2.11.0"
  val defSbtVersion = "0.13.5-M3"

  val analysis = AnalysisOptions()
  val analysisUtil = AnalysisUtil()
  val compileOrder = CompileOrder.Mixed
  val incOptions = IncOptions()

  def process (command :String, data :JMap[String,String]) {
    def get[T] (key :String, defval :T, fn :String => T) = data.get(key) match {
      case null => defval
      case text => fn(text)
    }
    if (command != "compile") sender.send("error", Map("cause" -> s"Unknown command: $command"))
    else {
      val cwd = get("cwd", null, new File(_))
      val output = get("output", null, new File(_))
      val classpath = get("classpath", Array[File](), _.split("\t").map(new File(_)))
      val javacOpts = get("jcopts", Array[String](), _.split("\t"))
      val scalacOpts = get("scopts", Array[String](), _.split("\t"))
      val scalacVersion = get("scvers", defScalacVersion, s => s)
      val sbtVersion = get("sbtvers", defSbtVersion, s => s)
      val logTrace = get("trace", false, _.toBoolean)

      val logger = new sbt.Logger {
        private var count = 0
        private def sendText (msg :String) {
          sender.send("log", Map("msg" -> msg))
          count += 1
        }
        def trace (t : =>Throwable) {
          if (logTrace) sendText(s"trace: $t")
        }
        def log (level :sbt.Level.Value, message : =>String) {
          if (logTrace || level >= sbt.Level.Info) sendText(message)
        }
        def success (message : =>String) = sendText(message)
      }

      val sources = get("sources", Array[File](), _.split("\t").map(new File(_)))
      val exsources = expand(sources, ArrayBuffer[File]())
      try {
        val inputs = Inputs.inputs(
          classpath,
          exsources,
          output,
          scalacOpts,
          javacOpts,
          analysis.cache,
          analysis.cacheMap,
          analysis.forceClean,
          false, // javaOnly
          compileOrder,
          incOptions,
          analysis.outputRelations,
          analysis.outputProducts,
          analysis.mirrorAnalysis)
        val vinputs = Inputs.verify(inputs)
        val compiler = Compiler(zincSetup(scalacVersion, sbtVersion, output), logger)
        compiler.compile(vinputs, Some(cwd))(logger)
        sender.send("compile", Map("result" -> "success"))
      } catch {
        case cf :CompileFailed =>
          sender.send("compile", Map("result" -> "failure"))
        case e :Exception =>
          logger.log(sbt.Level.Warn, s"Compiler choked $e")
          sender.send("compile", Map("result" -> "failure"))
      }
    }
  }

  private def file (root :File, comps :String*) = (root /: comps) { new File(_, _) }
  private val userHome = new File(System.getProperty("user.home"))
  private val m2root = file(userHome, ".m2", "repository")
  private def mavenJar (groupId :String, artifactId :String, version :String,
                        classifier :Option[String] = None) = {
    val suff = classifier.map(c => s"-$c").getOrElse("")
    val path = groupId.split("\\.") ++ Array(artifactId, version, s"$artifactId-$version$suff.jar")
    file(m2root, path :_*)
  }

  private def zincSetup (scalacVersion :String, sbtVersion :String, output :File) = Setup(
    mavenJar("org.scala-lang", "scala-compiler", scalacVersion),
    mavenJar("org.scala-lang", "scala-library", scalacVersion),
    Seq(mavenJar("org.scala-lang", "scala-reflect", scalacVersion)),
    mavenJar("com.typesafe.sbt", "sbt-interface", sbtVersion),
    mavenJar("com.typesafe.sbt", "compiler-interface", sbtVersion, Some("sources")),
    findJavaHome,
    false /*forkJava*/,
    file(output.getParentFile, "cache"))

  private def findJavaHome = {
    val home = new File(System.getProperty("java.home"))
    Some(if (home.getName == "jre") home.getParentFile else home)
  }

  private def expand (sources :Array[File], into :ArrayBuffer[File]) :ArrayBuffer[File] = {
    sources foreach { s =>
      if (s.isDirectory) expand(s.listFiles, into)
      else {
        val name = s.getName
        if ((name endsWith ".scala") || (name endsWith ".java")) into += s
      }
    }
    into
  }
}
