# Scaled Scala Compiler

This is a front-end for [Zinc] that makes it easier to control from Scaled. Zinc comes with built
in support to run as a Nailgun server, but that's not a great protocol for being controlled by
an IDE. This adapter expects to be run as a standalone process that communicates with its parent
via stdin/stdout.

Scaled forks off a subprocess that runs the Zinc server, sends it commands via stdin and reads
results from stdout. No nails or guns required.

This package will also eventually handle downloading and installing (via Maven Central) the
desired version of the Scala compiler, as needed. So if a request comes in to compile something
with scalac 2.10.3, the artifact for `org.scala-lang:scala-compiler:2.10.3` and its dependencies
will be downloaded and installed on demand.

## Distribution

Scaled Scala Compiler is released under the New BSD License. The most recent version of the code is
available at http://github.com/scaled/scala-compiler

[Scaled]: https://github.com/scaled/scaled
[Maven]: http://maven.apache.org/
