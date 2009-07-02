package scala.tools.nsc.interactive

import scala.collection._
import scala.tools.nsc.reporters.{Reporter, ConsoleReporter}
import scala.util.control.Breaks._

import dependencies._
import util.FakePos
import nsc.io.AbstractFile

/** A more defined build manager, based on change sets. For each
 *  updated source file, it computes the set of changes to its
 *  definitions, then checks all dependent units to see if the
 *  changes require a compilation. It repeats this process until
 *  a fixpoint is reached.
 */
class RefinedBuildManager(val settings: Settings) extends Changes with BuildManager {

  class BuilderGlobal(settings: Settings) extends nsc.Global(settings)  {

    object referencesAnalysis extends {
      val global: BuilderGlobal.this.type = BuilderGlobal.this
      val runsAfter = List("icode")
      val runsRightAfter = None
    } with References
    
    override def computeInternalPhases() {
      super.computeInternalPhases
      phasesSet += dependencyAnalysis
      phasesSet += referencesAnalysis
    }
  }
  val compiler = new BuilderGlobal(settings)
  import compiler.Symbol

  /** Managed source files. */
  private val sources: mutable.Set[AbstractFile] = new mutable.HashSet[AbstractFile]

  private val definitions: mutable.Map[AbstractFile, List[Symbol]] = 
    new mutable.HashMap[AbstractFile, List[Symbol]] {
      override def default(key: AbstractFile) = Nil
    }

  /** External references used by source file. */
  private var references: immutable.Map[AbstractFile, immutable.Set[String]] = _

  /** Add the given source files to the managed build process. */
  def addSourceFiles(files: Set[AbstractFile]) {
    sources ++= files
    update(files)
  }

  /** Remove the given files from the managed build process. */
  def removeFiles(files: Set[AbstractFile]) {
    sources --= files
  }

  /** The given files have been modified by the user. Recompile
   *  them and all files that depend on them. Only files that
   *  have been previously added as source files are recompiled.
   */
  def update(files: Set[AbstractFile]): Unit = if (!files.isEmpty) {
    val deps = compiler.dependencyAnalysis.dependencies
    val run = new compiler.Run()
    compiler.inform("compiling " + files)

    run.compileFiles(files.toList)
    if (compiler.reporter.hasErrors) {
      compiler.reporter.reset
      return
    }

    val changesOf = new mutable.HashMap[Symbol, List[Change]]

    val defs = compiler.referencesAnalysis.definitions
    for (val src <- files; val syms = defs(src); val sym <- syms) {
      definitions(src).find(_.fullNameString == sym.fullNameString) match {
        case Some(oldSym) => 
          changesOf(oldSym) = changeSet(oldSym, sym)
        case _ =>
          // a new top level definition, no need to process
      }
    }
    println("Changes: " + changesOf)
    updateDefinitions
    update(invalidated(files, changesOf))
  }

  /** Return the set of source files that are invalidated by the given changes. */
  def invalidated(files: Set[AbstractFile], changesOf: collection.Map[Symbol, List[Change]]): Set[AbstractFile] = {
    val buf = new mutable.HashSet[AbstractFile]    
    var directDeps = 
      compiler.dependencyAnalysis.dependencies.dependentFiles(1, files)

//    println("direct dependencies on " + files + " " + directDeps)
    def invalidate(file: AbstractFile, reason: String, change: Change) = {
      println("invalidate " + file + " because " + reason + " [" + change + "]")
      buf += file
      directDeps -= file
      break
    }

    for ((oldSym, changes) <- changesOf; change <- changes) {

      def checkParents(cls: Symbol, file: AbstractFile) {
        val parentChange = cls.info.parents.exists(_.typeSymbol.fullNameString == oldSym.fullNameString)
//        println("checkParents " + cls + " oldSym: " + oldSym + " parentChange: " + parentChange + " " + cls.info.parents)
        change match {
          case Changed(Class(_)) if parentChange =>
            invalidate(file, "parents have changed", change)
        
          case Added(Definition(_)) if parentChange =>
            invalidate(file, "inherited new method", change)

          case Removed(Definition(_)) if parentChange =>
            invalidate(file, "inherited method removed", change)
          
          case _ => ()
        }
      }

      def checkInterface(cls: Symbol, file: AbstractFile) {
        change match {
          case Added(Definition(name)) =>
            if (cls.info.decls.iterator.exists(_.fullNameString == name))
              invalidate(file, "of new method with existing name", change)
          case Changed(Class(name)) =>
            if (cls.info.typeSymbol.fullNameString == name)
              invalidate(file, "self type changed", change)
          case _ =>
            ()
        }
      }

      def checkReferences(file: AbstractFile) {
//        println(file + ":" + references(file))
        val refs = references(file)
        change match {
          case Removed(Definition(name)) if refs(name) =>
            invalidate(file, " it references deleted definition", change)
          case Removed(Class(name)) if (refs(name)) =>
            invalidate(file, " it references deleted class", change)
          case Changed(Definition(name)) if (refs(name)) =>
            invalidate(file, " it references changed definition", change)
          case _ => ()
        }
      }

      breakable {
        for (file <- directDeps) {
          for (cls <- definitions(file)) checkParents(cls, file)
          for (cls <- definitions(file)) checkInterface(cls, file)
          checkReferences(file)
        }
      }
    }
    buf
  }

  /** Update the map of definitions per source file */
  private def updateDefinitions {
    for ((src, localDefs) <- compiler.referencesAnalysis.definitions) {
      definitions(src) = (localDefs map (_.cloneSymbol))
    }
    this.references = compiler.referencesAnalysis.references
  }

  /** Load saved dependency information. */
  def loadFrom(file: AbstractFile) {
    compiler.dependencyAnalysis.loadFrom(file)
  }
  
  /** Save dependency information to `file'. */
  def saveTo(file: AbstractFile) {
    compiler.dependencyAnalysis.dependenciesFile = file
    compiler.dependencyAnalysis.saveDependencies()
  }
}