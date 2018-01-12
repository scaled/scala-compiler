//
// Scaled Scala Compiler - a front-end for Zinc used by Scaled's Scala support
// http://github.com/scaled/scala-compiler/blob/master/LICENSE

package sbt

import java.io.File
import java.net.URLClassLoader
import java.util.Optional
import java.util.function.{ Function => JFunction }

import sbt.internal.inc._
import sbt.internal.inc.classpath.ClassLoaderCache
import sbt.io.IO
import sbt.io.syntax._
import sbt.librarymanagement._
import sbt.librarymanagement.ivy._
import sbt.util.{ InterfaceUtil, Logger }
import xsbti.{Position, Reporter}
import xsbti.compile.{ ScalaInstance => _, _ }

object ZincProvider {

  val homeDir = new File(System.getProperty("user.home"))
  val ivyHome = new File(homeDir, ".ivy2")

  val resolvers = Array(ZincComponentCompiler.LocalResolver, Resolver.mavenCentral)
  private def ivyConfiguration (targetDir :File, log :Logger) =
    getDefaultConfiguration(targetDir, ivyHome, resolvers, log)

  private def getDefaultConfiguration (
    baseDirectory :File, ivyHome :File, resolvers0 :Array[Resolver], log :xsbti.Logger
  ) :IvyConfiguration = {
    import sbt.io.syntax._
    val resolvers = resolvers0.toVector
    val chainResolver = ChainedResolver("zinc-chain", resolvers)
    InlineIvyConfiguration().
      withPaths(IvyPaths(baseDirectory, Some(ivyHome))).
      withResolvers(resolvers).
      withModuleConfigurations(Vector(ModuleConfiguration("*", chainResolver))).
      withLock(None).
      withChecksums(Vector.empty).
      withResolutionCacheDir(ivyHome / "resolution-cache").
      withUpdateOptions(UpdateOptions()).
      withLog(log)
  }

  def secondaryCacheDirectory (targetDir :File) :File = new File(targetDir, "zinc-components")

  def getZincProvider (targetDir :File, log :Logger): CompilerBridgeProvider = {
    val lock = ZincComponentCompiler.getDefaultLock
    val secondaryCache = Some(secondaryCacheDirectory(targetDir))
    val componentProvider = ZincComponentCompiler.getDefaultComponentProvider(targetDir)
    val manager = new ZincComponentManager(lock, componentProvider, secondaryCache, log)
    val dependencyResolution = IvyDependencyResolution(ivyConfiguration(targetDir, log))
    ZincComponentCompiler.interfaceProvider(
      manager, dependencyResolution, new File(targetDir, "lib_managed"))
  }
}
