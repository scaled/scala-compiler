//
// Scaled Scala Compiler - a front-end for Zinc used by Scaled's Scala support
// http://github.com/scaled/scala-compiler/blob/master/LICENSE

package scaled.zinc

import java.io.IOException
import java.util.{Map => JMap}
import scaled.prococol.{Sender, Receiver}

object Main {

  val sender = new Sender(System.out, true)
  val server = new Server(sender)

  def main (args :Array[String]) {
    // read prococol messages from stdin and pass them to server
    val recv = new Receiver(System.in, server)
    recv.run()
  }
}
