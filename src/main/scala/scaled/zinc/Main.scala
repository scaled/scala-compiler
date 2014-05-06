//
// Scaled Scala Compiler - a front-end for Zinc used by Scaled's Scala support
// http://github.com/scaled/scala-compiler/blob/master/LICENSE

package scaled.zinc

import java.io.{BufferedReader, InputStreamReader}
import scala.annotation.tailrec

object Main {

  val server = new Server()

  def main (args :Array[String]) {
    println("hello")
    try {
      // we just read commands from stdin and pass them to the server for processing
      val in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"))
      @tailrec def loop (line :String) {
        if (line == null) return // exit
        else line.split(" ", 2) match {
          case Array("exit") => return // exit
          case Array(cmd, args) => server.process(cmd, args)
          case _ => println(s"invalid: $line")
        }
        loop(in.readLine)
      }
      loop(in.readLine)
    } catch {
      case e :Throwable => e.printStackTrace(System.err)
    }
    println("goodbye")
  }
}
