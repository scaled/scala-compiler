//
// Scaled Scala Compiler - a front-end for Zinc used by Scaled's Scala support
// http://github.com/scaled/scala-compiler/blob/master/LICENSE

package scaled.zinc

import java.io.File
import java.util.{Map => JMap, HashMap}
import scala.collection.mutable.ArrayBuffer
import scaled.prococol.{Receiver, Sender}
import sbt.util.{Logger, Level}
import xsbti.{CompileFailed, Position, Problem, Severity, Reporter}

class Server (sender :Sender) extends Receiver.Listener {
  import scala.collection.JavaConverters._

  val defScalacVersion = "2.12.0"
  val defSbtVersion = "0.13.5"

  // TODO: cap these at one or two in memory
  val setups = new HashMap[String, Zinc.CompilerSetup]()

  private def send (msg :String, args :Map[String, String]) = sender.send(msg, args.asJava)
  private def sendLog (msg :String) = send("log", Map("msg" -> msg))

  def onMessage (command :String, data :JMap[String,String]) = command match {
    case "compile" => compile(data)
    case _         => send("error", Map("cause" -> s"Unknown command: $command"))
  }

  private def compile (data :JMap[String,String]) {
    def get[T] (key :String, defval :T, fn :String => T) = data.get(key) match {
      case null => defval
      case text => fn(text)
    }
    def untabsep (text :String) = if (text == "") Array[String]() else text.split("\t")
    val cwd = get("cwd", null, new File(_))
    val sessionId = get("sessionid", "<none>", s => s)
    val target = get("target", null, new File(_))
    val output = get("output", null, new File(_))
    val classpath = get("classpath", Array[File](), untabsep(_).map(new File(_)))
    val javacOpts = get("jcopts", Array[String](), untabsep(_))
    val scalacOpts = get("scopts", Array[String](), untabsep(_))
    val scalacVersion = get("scvers", defScalacVersion, s => s)
    val sbtVersion = get("sbtvers", defSbtVersion, s => s)
    val forceClean = get("preclean", false, _.toBoolean)
    val logTrace = get("trace", false, _.toBoolean)

    val logger = new Logger {
      def trace (t : =>Throwable) :Unit = if (logTrace) sendLog(exToString(t))
      def log (level :Level.Value, message : =>String) :Unit =
        if (logTrace || level >= Level.Info) sendLog(s"scalac ($level): $message")
      def success (message : =>String) :Unit = sendLog(s"scalac: $message")
    }

    val reporter = new Reporter {
      var _problems = ArrayBuffer[Problem]()
      def reset () :Unit = _problems.clear()
      def hasWarnings = _problems.exists(_.severity == Severity.Warn)
      def hasErrors = _problems.exists(_.severity == Severity.Error)
      def problems :Array[Problem] = _problems.toArray
      def log (problem :Problem) {
        sendLog(problem.toString)
        _problems += problem
      }
      def printSummary () {}
      def comment (pos :Position, msg :String) :Unit = sendLog(s"Reporter.comment $pos $msg")
    }

    val sources = get("sources", Array[File](), _.split("\t").map(new File(_)))
    val sourceFiles = expand(sources, ArrayBuffer[File]()).toArray
    try {
      val setup = setups.get(sessionId) match {
        case null =>
          val newSetup = Zinc.CompilerSetup(logger, reporter, target, scalacVersion)
          setups.put(sessionId, newSetup)
          newSetup
        case setup =>
          // TODO: validate config still matches (not likely, but if scalaVersion or target
          // directory somehow changed, we'd want to reset)
          setup
      }

      val inputs = setup.mkInputs(classpath, sourceFiles, output, scalacOpts, javacOpts)
      setup.doCompile(inputs)
      send("compile", Map("result" -> "success"))

    } catch {
      case f :CompileFailed =>
        send("compile", Map("result" -> "failure"))
      case e :Exception =>
        sendLog(exToString(e))
        send("compile", Map("result" -> "failure"))
    }
  }

  private def exToString (ex :Throwable) = {
    val sw = new java.io.StringWriter()
    ex.printStackTrace(new java.io.PrintWriter(sw))
    sw.toString
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
