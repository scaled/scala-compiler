//
// Scaled Scala Compiler - a front-end for Zinc used by Scaled's Scala support
// http://github.com/scaled/scala-compiler/blob/master/LICENSE

package scaled.zinc

import com.typesafe.zinc._
import java.io.File
import sbt.compiler.CompileFailed
import scala.collection.mutable.ArrayBuffer
import xsbti.compile.CompileOrder

class Server {

  val logger = new sbt.Logger {
    def trace (t : =>Throwable) {
      if (Server.this.trace) System.out.println(s"trace: $t")
    }
    def log (level :sbt.Level.Value, message : =>String) {
      if (Server.this.trace || level >= sbt.Level.Info) System.out.println(message)
    }
    def success (message : =>String) :Unit = System.out.println(message)
  }

  var cwd :File = _
  var trace = false
  var output :File = _
  var classpath = Array[File]()
  var javacOpts = Array[String]()
  var scalacOpts = Array[String]()

  var scalacVersion = "2.11.0"
  var sbtVersion = "0.13.5-M3"

  val analysis = AnalysisOptions()
  val analysisUtil = AnalysisUtil()
  val compileOrder = CompileOrder.Mixed
  val incOptions = IncOptions()

  def process (command :String, args :String) :Unit = command match {
    case "cwd"       => cwd = new File(args)
    case "output"    => output = new File(args)
    case "classpath" => classpath = args.split("\t").map(new File(_))
    case "scvers"    => scalacVersion = args
    case "jcopts"    => javacOpts = args.split("\t")
    case "scopts"    => scalacOpts = args.split("\t")
    case "compile"   => compile(args.split("\t").map(new File(_)))
    case _           => println("unknown command: $command")
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

  private def zincSetup = Setup(
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

  private def compile (sources :Array[File]) :Unit = try {
    val exsources = expand(sources, ArrayBuffer[File]())
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
    val compiler = Compiler(zincSetup, logger)
    compiler.compile(vinputs, Some(cwd))(logger)
    println("compile success")
  } catch {
    case cf :CompileFailed => println("compile failure")
    case e :Exception => println(e.getClass) ; e.printStackTrace(System.out)
  }
}
