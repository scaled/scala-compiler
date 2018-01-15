//
// Scaled Scala Compiler - a front-end for Zinc used by Scaled's Scala support
// http://github.com/scaled/scala-compiler/blob/master/LICENSE

package scaled.zinc

import java.io.File
import java.net.URLClassLoader
import java.util.Optional

import sbt.internal.inc._
import sbt.internal.inc.classpath.ClassLoaderCache
import sbt.io.IO
import sbt.io.syntax._
import sbt.util.{InterfaceUtil, Logger}
import xsbti.{Position, Reporter}
import xsbti.compile.{ScalaInstance, _}

// cobbled together from Zinc unit/integration tests and some other random shit I found on the
// Internets; beware of potential cargo cultery
object Zinc {

  def getCompilerBridge (targetDir :File, logger :Logger, scalaVersion :String) :File = {
    val provider = sbt.ZincProvider.getZincProvider(targetDir, logger)
    val scalaInstance = provider.fetchScalaInstance(scalaVersion, logger)
    val bridge = provider.fetchCompiledBridge(scalaInstance, logger)
    val target = targetDir / s"target-bridge-$scalaVersion.jar"
    IO.copyFile(bridge, target)
    target
  }

  def scalaInstance (scalaVersion :String, targetDir :File, logger :Logger) :ScalaInstance = {
    val provider = sbt.ZincProvider.getZincProvider(targetDir, logger)
    provider.fetchScalaInstance(scalaVersion, logger)
  }

  def scalaCompiler (instance :ScalaInstance, bridgeJar :File) :AnalyzingCompiler = {
    val bridgeProvider = ZincUtil.constantBridgeProvider(instance, bridgeJar)
    val classpath = ClasspathOptionsUtil.boot
    val cache = Some(new ClassLoaderCache(new URLClassLoader(Array())))
    new AnalyzingCompiler(instance, bridgeProvider, classpath, _ => (), cache)
  }

  val lookup = new PerClasspathEntryLookup {
    override def analysis (classpathEntry :File) :Optional[CompileAnalysis] =
      Optional.empty[CompileAnalysis]
    override def definesClass (classpathEntry :File) :DefinesClass =
      Locate.definesClass(classpathEntry)
  }

  case class CompilerSetup (logger :Logger, reporter :Reporter, targetDir :File,
                            scalaVersion :String, incOptions :IncOptions = IncOptions.of()) {
    val compiler = new IncrementalCompilerImpl
    val compilerBridge = getCompilerBridge(targetDir, logger, scalaVersion)

    val sinst = scalaInstance(scalaVersion, targetDir, logger)
    val scomp = scalaCompiler(sinst, compilerBridge)
    val comps = compiler.compilers(sinst, ClasspathOptionsUtil.boot, None, scomp)

    val maxErrors = 100 // TODO :configure?

    var lastAnalysis :Option[AnalysisContents] = None
    var lastCompiledUnits :Set[String] = Set.empty
    val progress = new CompileProgress {
      override def advance (current :Int, total :Int) = true
      override def startUnit (phase :String, unitPath :String) { lastCompiledUnits += unitPath }
    }

    val cacheFile = new File(targetDir, "inc_compile")

    def mkOptions (classpath :Seq[File], sourceFiles :Array[File], classesDir :File,
                  scalacOpts :Array[String], javacOpts :Array[String]) = {
      val fullcp = Array(classesDir) ++ sinst.allJars ++ classpath
      CompileOptions.of(fullcp, sourceFiles, classesDir, scalacOpts, javacOpts, maxErrors,
                        pos => pos /* source position mapper */, CompileOrder.Mixed)
    }

    def doCompile (opts :CompileOptions, reporter :Reporter) :CompileResult = {
      val setup = compiler.setup(lookup, skip = false, cacheFile, CompilerCache.fresh, incOptions,
                                 reporter, Some(progress), Array())
      val inputs = compiler.inputs(opts, comps, setup, lastAnalysis match {
        case None    => compiler.emptyPreviousResult
        case Some(a) => PreviousResult.of(Optional.of(a.getAnalysis), Optional.of(a.getMiniSetup))
      })
      lastCompiledUnits = Set.empty
      val result = compiler.compile(inputs, logger)
      lastAnalysis = Some(AnalysisContents.create(result.analysis(), result.setup()))
      result
    }
  }
}
