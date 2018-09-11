// Copyright (c) 2017-2018 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package rsc.outline

import rsc.gensym._
import rsc.report._
import rsc.semantics._
import rsc.settings._
import rsc.syntax._
import rsc.util._

// FIXME: https://github.com/twitter/rsc/issues/98
final class Synthesizer private (
    settings: Settings,
    reporter: Reporter,
    gensyms: Gensyms,
    symtab: Symtab,
    todo: Todo) {
  private lazy val scheduler = {
    Scheduler(settings, reporter, gensyms, symtab, todo)
  }

  def caseClassMembers(env: Env, tree: DefnClass): Unit = {
    caseClassCopy(env, tree)
    caseClassProductPrefix(env, tree)
    caseClassProductArity(env, tree)
    caseClassProductElement(env, tree)
    caseClassProductIterator(env, tree)
    caseClassCanEqual(env, tree)
    caseClassHashCode(env, tree)
    caseClassToString(env, tree)
    caseClassEquals(env, tree)
  }

  def caseClassCompanionMembers(env: Env, tree: DefnClass): Unit = {
    caseClassCompanionToString(env, tree)
    caseClassCompanionApply(env, tree)
    caseClassCompanionUnapply(env, tree)
  }

  def caseObjectMembers(env: Env, tree: DefnObject): Unit = {
    caseObjectProductPrefix(env, tree)
    caseObjectProductArity(env, tree)
    caseObjectProductElement(env, tree)
    caseObjectProductIterator(env, tree)
    caseObjectCanEqual(env, tree)
    caseObjectHashCode(env, tree)
    caseObjectToString(env, tree)
  }

  def defaultGetters(env: Env, tree: DefnClass): Unit = {
    tree.primaryCtor.foreach(defaultGetters(env, tree.tparams, _))
    tree.stats.foreach {
      case ctor: DefnCtor => defaultGetters(env, tree.tparams, ctor)
      case _ => ()
    }
  }

  def defaultGetters(env: Env, tree: DefnDef): Unit = {
    defaultGetters(env, tree.tparams, tree)
  }

  private def defaultGetters(env: Env, treeTparams: List[TypeParam], tree: Parameterized): Unit = {
    var paramPos = 0
    val treeParamss = tree.paramss
    treeParamss.zipWithIndex.foreach {
      case (params, i) =>
        params.foreach { param =>
          paramPos += 1
          if (param.rhs.nonEmpty) {
            val mods = Mods(tree.mods.trees.flatMap {
              case mod: ModPrivate =>
                Some(mod)
              case mod: ModPrivateThis =>
                Some(ModPrivate())
              case mod: ModPrivateWithin =>
                if (settings.abi == Scalac211) None
                else Some(mod)
              case mod: ModProtected =>
                Some(mod)
              case mod: ModProtectedThis =>
                Some(ModProtected())
              case mod: ModProtectedWithin =>
                if (settings.abi == Scalac211) Some(ModProtected())
                else Some(mod)
              case mod: ModFinal =>
                Some(mod)
              case _ =>
                None
            })
            val id = TermId(tree.id.nameopt.get.value + "$default$" + paramPos)
            val tparams = treeTparams.map { tp =>
              val mods = Mods(Nil)
              val id = TptId(tp.id.nameopt.get.value).withPos(tp.id.pos)
              val lbound = tp.lbound.map(_.dupe)
              val ubound = tp.ubound.map(_.dupe)
              val tparam = TypeParam(mods, id, Nil, lbound, ubound, Nil, Nil)
              tparam.withPos(tp.pos)
            }
            val paramss = treeParamss
              .take(i)
              .map(_.map { p =>
                val mods = Mods(Nil)
                val id = TermId(p.id.nameopt.get.value)
                val tpt = p.tpt.map(_.dupe)
                val param = Param(mods, id, tpt, None)
                param.withPos(p.pos)
              })
            val ret = {
              val paramTpt = param.tpt.map(_.dupe).map {
                case TptByName(tpt) => tpt
                case tpt => tpt
              }
              paramTpt.map { paramTpt =>
                val annotSym = UncheckedVarianceClass
                val annotTpt = TptId("uncheckedVariance").withSym(annotSym)
                val annot = ModAnnotation(Init(annotTpt, Nil))
                TptAnnotate(paramTpt, Mods(List(annot)))
              }
            }
            val rhs = Some(TermStub())
            val meth = DefnMethod(mods, id, tparams, paramss, ret, rhs)
            scheduler(env, meth.withPos(param.pos))
          }
        }
    }
  }

  def enumMembers(env: Env, tree: DefnEnum): Unit = {
    ???
  }

  def implicitClassConversion(env: Env, tree: DefnClass): Unit = {
    val mods = Mods {
      tree.mods.trees.filter(_.isInstanceOf[ModAccess]) ++ List(ModImplicit())
    }
    val id = TermId(tree.id.value)
    val tparams = tree.tparams.map { tp =>
      val id = TptId(tp.id.nameopt.get.value).withPos(tp.id.pos)
      val lbound = tp.lbound.map(_.dupe)
      val ubound = tp.ubound.map(_.dupe)
      val tparam = TypeParam(Mods(Nil), id, Nil, lbound, ubound, Nil, Nil)
      tparam.withPos(tp.pos)
    }
    val paramss = {
      val paramss = symtab._paramss.get(tree.primaryCtor.get)
      if (paramss != null) {
        paramss match {
          case List(List(param)) =>
            val pos = param.id.pos
            val id = TermId(param.id.nameopt.get.value).withPos(pos)
            val tpt = param.tpt.map(_.dupe)
            val methParam = Param(Mods(Nil), id, tpt, None)
            List(List(methParam.withPos(param.pos)))
          case _ =>
            Nil
        }
      } else {
        crash(tree)
      }
    }
    val ret = {
      val core = tree.id
      if (tparams.isEmpty) Some(core)
      else Some(TptParameterize(core, tparams.map(_.id.asInstanceOf[TptId])))
    }
    val rhs = Some(TermStub())
    val meth = DefnMethod(mods, id, tparams, paramss, ret, rhs)
    scheduler(env, meth.withPos(tree.pos))
  }

  def paramAccessors(env: Env, tree: DefnClass): Unit = {
    tree.primaryCtor.foreach { primaryCtor =>
      val paramss = symtab._paramss.get(primaryCtor)
      paramss.zipWithIndex.foreach {
        case (params, i) =>
          params.foreach { param =>
            val fieldMods = {
              val valMods = {
                if (param.hasVal || param.hasVar) Nil
                else List(ModVal())
              }
              val accessMods = {
                if (param.hasPrivate) Nil
                else if (param.hasPrivateThis) Nil
                else if (param.hasPrivateWithin) Nil
                else if (param.hasProtected) Nil
                else if (param.hasProtectedThis) Nil
                else if (param.hasProtectedWithin) Nil
                else if (param.hasVal) Nil
                else if (param.hasVar) Nil
                else {
                  if (tree.hasCase && i == 0) Nil
                  else List(ModPrivateThis())
                }
              }
              Mods(accessMods ++ param.mods.trees ++ valMods)
            }
            val fieldId = TermId(param.id.nameopt.get.value).withPos(param.pos)
            val fieldTpt = param.tpt.map(_.dupe)
            val fieldRhs = Some(TermStub())
            val field = DefnField(fieldMods, fieldId, fieldTpt, fieldRhs)
            scheduler(env, field.withPos(param.pos))
          }
      }
    }
  }

  def paramss(env: Env, tree: Parameterized): Unit = {
    val paramss = symtab._paramss.get(tree)
    if (paramss != null) {
      ()
    } else {
      val tparams = {
        tree match {
          case _: DefnCtor | _: PrimaryCtor =>
            val enclosingClass = env._scopes.collectFirst {
              case x: TemplateScope if x.tree.isInstanceOf[DefnClass] => x.tree
            }
            enclosingClass.map(_.tparams).getOrElse(Nil)
          case _ =>
            tree.tparams
        }
      }
      var pendingEvidences = {
        tparams.exists(tp => tp.vbounds.nonEmpty || tp.cbounds.nonEmpty)
      }
      if (pendingEvidences) {
        def evidenceParams: List[Param] = {
          val gensym = gensyms(tree)
          val paramsBuf = List.newBuilder[Param]
          tparams.foreach { tparam =>
            tparam.id match {
              case tparamId: TptId =>
                tparam.vbounds.foreach { vbound =>
                  val mods = Mods(List(ModImplicit()))
                  val id = TermId(gensym.evidence())
                  val tpt = {
                    val core = TptId("Function1").withSym(FunctionClass(1))
                    TptParameterize(core, List(tparamId, vbound))
                  }
                  val param = Param(mods, id, Some(tpt), None)
                  paramsBuf += param.withPos(vbound.pos)
                }
                tparam.cbounds.foreach { cbound =>
                  val mods = Mods(List(ModImplicit()))
                  val id = {
                    val result = TermId(gensym.evidence())
                    tree match {
                      case _: PrimaryCtor => result.withPos(cbound.pos)
                      case _ => result
                    }
                  }
                  val tpt = TptParameterize(cbound, List(tparamId))
                  val param = Param(mods, id, Some(tpt), None)
                  paramsBuf += param.withPos(cbound.pos)
                }
              case _ =>
                ()
            }
          }
          paramsBuf.result
        }
        val paramssBuf = List.newBuilder[List[Param]]
        tree.paramss.foreach { params =>
          val paramsBuf = List.newBuilder[Param]
          params.foreach { param =>
            if (param.hasImplicit && pendingEvidences) {
              evidenceParams.foreach(paramsBuf.+=)
              pendingEvidences = false
            }
            paramsBuf += param
          }
          paramssBuf += paramsBuf.result
        }
        if (pendingEvidences) {
          paramssBuf += evidenceParams
          pendingEvidences = false
        }
        symtab._paramss.put(tree, paramssBuf.result)
      } else {
        symtab._paramss.put(tree, tree.paramss)
      }
    }
  }

  def syntheticCompanion(env: Env, tree: DefnClass): Unit = {
    val mods = Mods(tree.mods.trees.filter(_.isInstanceOf[ModAccess]))
    val id = TermId(tree.id.value)
    val companion = DefnObject(mods, id, Nil, Nil, None, Nil)
    scheduler(env, companion.withPos(tree.pos))
  }

  def valueClassMembers(env: Env, tree: DefnClass): Unit = {
    valueClassEquals(env, tree)
    valueClassHashCode(env, tree)
  }

  private def caseClassCopy(env: Env, tree: DefnClass): Unit = {
    val id = TermId("copy")
    val tparams = tree.tparams.map { tp =>
      val id = TptId(tp.id.nameopt.get.value).withPos(tp.id.pos)
      val lbound = tp.lbound.map(_.dupe)
      val ubound = tp.ubound.map(_.dupe)
      val tparam = TypeParam(Mods(Nil), id, Nil, lbound, ubound, Nil, Nil)
      tparam.withPos(tp.pos)
    }
    val paramss = tree.primaryCtor.get.paramss.zipWithIndex.map {
      case (params, i) =>
        params.map { p =>
          val id = TermId(p.id.nameopt.get.value).withPos(p.id.pos)
          val tpt = p.tpt.map(_.dupe)
          val rhs = if (i == 0) Some(TermStub()) else None
          val param = Param(Mods(Nil), id, tpt, rhs)
          param.withPos(p.pos)
        }
    }
    val ret = {
      val core = tree.id
      if (tparams.isEmpty) Some(core)
      else Some(TptParameterize(core, tparams.map(_.id.asInstanceOf[TptId])))
    }
    val rhs = Some(TermStub())
    val meth = DefnMethod(Mods(Nil), id, tparams, paramss, ret, rhs)
    scheduler(env, meth.withPos(tree.pos))

    tree.primaryCtor.get.paramss.head.zipWithIndex.foreach {
      case (param, i) =>
        val mods = Mods(Nil)
        val id = TermId("copy$default$" + (i + 1))
        val tparams = tree.tparams.map { tp =>
          val mods = Mods(Nil)
          val id = TptId(tp.id.nameopt.get.value).withPos(tp.id.pos)
          val lbound = tp.lbound.map(_.dupe)
          val ubound = tp.ubound.map(_.dupe)
          val tparam = TypeParam(mods, id, Nil, lbound, ubound, Nil, Nil)
          tparam.withPos(tp.pos)
        }
        val ret = {
          val annotSym = UncheckedVarianceClass
          val annotTpt = TptId("uncheckedVariance").withSym(annotSym)
          val annot = ModAnnotation(Init(annotTpt, Nil))
          Some(TptAnnotate(param.tpt.get, Mods(List(annot))))
        }
        val rhs = Some(TermStub())
        val meth = DefnMethod(mods, id, tparams, Nil, ret, rhs)
        scheduler(env, meth.withPos(param.pos))
    }
  }

  private def caseClassProductPrefix(env: Env, tree: DefnClass): Unit = {
    caseProductPrefix(env, tree)
  }

  private def caseClassProductArity(env: Env, tree: DefnClass): Unit = {
    caseProductArity(env, tree)
  }

  private def caseClassProductElement(env: Env, tree: DefnClass): Unit = {
    caseProductElement(env, tree)
  }

  private def caseClassProductIterator(env: Env, tree: DefnClass): Unit = {
    caseProductIterator(env, tree)
  }

  private def caseClassCanEqual(env: Env, tree: DefnClass): Unit = {
    caseCanEqual(env, tree)
  }

  private def caseClassHashCode(env: Env, tree: DefnClass): Unit = {
    caseHashCode(env, tree)
  }

  private def caseClassToString(env: Env, tree: DefnClass): Unit = {
    caseToString(env, tree)
  }

  private def caseClassEquals(env: Env, tree: DefnClass): Unit = {
    val id = TermId("equals")
    val paramss = {
      val tpt = TptId("Any").withSym(AnyClass)
      val param = Param(Mods(Nil), TermId("x$1"), Some(tpt), None)
      List(List(param.withPos(tree.pos)))
    }
    val ret = Some(TptId("Boolean").withSym(BooleanClass))
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, paramss, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }

  private def caseClassCompanionToString(env: Env, tree: DefnClass): Unit = {
    val companionTree = symtab._outlines.get(tree.id.sym.companionObject)
    companionTree match {
      case companionTree: DefnObject if companionTree.isSynthetic =>
        val mods = Mods(List(ModFinal()))
        val id = TermId("toString")
        val paramss = List(Nil)
        val ret = Some(TptId("String").withSym(StringClass))
        val rhs = Some(TermStub())
        val method = DefnMethod(mods, id, Nil, paramss, ret, rhs)
        scheduler(env, method.withPos(tree.pos))
      case _ =>
        ()
    }
  }

  private def caseClassCompanionApply(env: Env, tree: DefnClass): Unit = {
    val id = TermId("apply")
    val tparams = tree.tparams.map { tp =>
      val id = TptId(tp.id.nameopt.get.value).withPos(tp.id.pos)
      val lbound = tp.lbound.map(_.dupe)
      val ubound = tp.ubound.map(_.dupe)
      val tparam = TypeParam(Mods(Nil), id, Nil, lbound, ubound, Nil, Nil)
      tparam.withPos(tp.pos)
    }
    var hasDefaultParams = false
    val paramss = tree.primaryCtor.get.paramss.map(_.map { p =>
      val id = TermId(p.id.nameopt.get.value).withPos(p.id.pos)
      val tpt = p.tpt.map(_.dupe)
      val rhs = p.rhs.map(_.dupe)
      hasDefaultParams |= p.rhs.nonEmpty
      val param = Param(Mods(Nil), id, tpt, rhs)
      param.withPos(p.pos)
    })
    val ret = {
      val core = tree.id
      if (tparams.isEmpty) Some(core)
      else Some(TptParameterize(core, tparams.map(_.id.asInstanceOf[TptId])))
    }
    val rhs = Some(TermStub())
    val meth = DefnMethod(Mods(Nil), id, tparams, paramss, ret, rhs)
    scheduler(env, meth.withPos(tree.pos))
    if (hasDefaultParams) defaultGetters(env, meth)
  }

  private def caseClassCompanionUnapply(env: Env, tree: DefnClass): Unit = {
    val id = TermId("unapply")
    val tparams = tree.tparams.map { tp =>
      val id = TptId(tp.id.nameopt.get.value).withPos(tp.id.pos)
      val lbound = tp.lbound.map(_.dupe)
      val ubound = tp.ubound.map(_.dupe)
      val tparam = TypeParam(Mods(Nil), id, Nil, lbound, ubound, Nil, Nil)
      tparam.withPos(tp.pos)
    }
    val paramss = {
      val tpt = {
        val core = tree.id
        if (tparams.isEmpty) core
        else TptParameterize(core, tparams.map(_.id.asInstanceOf[TptId]))
      }
      val param = Param(Mods(Nil), TermId("x$0"), Some(tpt), None)
      List(List(param.withPos(tree.pos)))
    }
    val ret = {
      val params = tree.primaryCtor.get.paramss.headOption.getOrElse(Nil)
      params match {
        case Nil =>
          Some(TptId("Boolean").withSym(BooleanClass))
        case List(param) =>
          val option = TptId("Option").withSym(OptionClass)
          Some(TptParameterize(option, List(param.tpt.get.dupe)))
        case params =>
          val option = TptId("Option").withSym(OptionClass)
          val tuple = TptTuple(params.map(_.tpt.get.dupe))
          Some(TptParameterize(option, List(tuple)))
      }
    }
    val rhs = Some(TermStub())
    val meth = DefnMethod(Mods(Nil), id, tparams, paramss, ret, rhs)
    scheduler(env, meth.withPos(tree.pos))
  }

  private def caseObjectProductPrefix(env: Env, tree: DefnObject): Unit = {
    caseProductPrefix(env, tree)
  }

  private def caseObjectProductArity(env: Env, tree: DefnObject): Unit = {
    caseProductArity(env, tree)
  }

  private def caseObjectProductElement(env: Env, tree: DefnObject): Unit = {
    caseProductElement(env, tree)
  }

  private def caseObjectProductIterator(env: Env, tree: DefnObject): Unit = {
    caseProductIterator(env, tree)
  }

  private def caseObjectCanEqual(env: Env, tree: DefnObject): Unit = {
    caseCanEqual(env, tree)
  }

  private def caseObjectHashCode(env: Env, tree: DefnObject): Unit = {
    caseHashCode(env, tree)
  }

  private def caseObjectToString(env: Env, tree: DefnObject): Unit = {
    caseToString(env, tree)
  }

  private def caseProductPrefix(env: Env, tree: DefnTemplate): Unit = {
    val id = TermId("productPrefix")
    val ret = Some(TptId("String").withSym(StringClass))
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, Nil, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }

  private def caseProductArity(env: Env, tree: DefnTemplate): Unit = {
    val id = TermId("productArity")
    val ret = Some(TptId("Int").withSym(IntClass))
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, Nil, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }

  private def caseProductElement(env: Env, tree: DefnTemplate): Unit = {
    val id = TermId("productElement")
    val paramss = {
      val tpt = TptId("Int").withSym(IntClass)
      val param = Param(Mods(Nil), TermId("x$1"), Some(tpt), None)
      List(List(param.withPos(tree.pos)))
    }
    val ret = Some(TptId("Any").withSym(AnyClass))
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, paramss, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }

  private def caseProductIterator(env: Env, tree: DefnTemplate): Unit = {
    val id = TermId("productIterator")
    val ret = {
      val iterator = TptId("Iterator").withSym(IteratorClass)
      val any = TptId("Any").withSym(AnyClass)
      Some(TptParameterize(iterator, List(any)))
    }
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, Nil, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }

  private def caseCanEqual(env: Env, tree: DefnTemplate): Unit = {
    val id = TermId("canEqual")
    val paramss = {
      val tpt = TptId("Any").withSym(AnyClass)
      val param = Param(Mods(Nil), TermId("x$1"), Some(tpt), None)
      List(List(param.withPos(tree.pos)))
    }
    val ret = Some(TptId("Boolean").withSym(BooleanClass))
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, paramss, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }

  private def caseHashCode(env: Env, tree: DefnTemplate): Unit = {
    val id = TermId("hashCode")
    val paramss = List(Nil)
    val ret = Some(TptId("Int").withSym(IntClass))
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, paramss, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }

  private def caseToString(env: Env, tree: DefnTemplate): Unit = {
    val id = TermId("toString")
    val paramss = List(Nil)
    val ret = Some(TptId("String").withSym(StringClass))
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, paramss, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }

  private def valueClassEquals(env: Env, tree: DefnClass): Unit = {
    val id = TermId("equals")
    val paramss = {
      val tpt = TptId("Any").withSym(AnyClass)
      val param = Param(Mods(Nil), TermId("x$1"), Some(tpt), None)
      List(List(param.withPos(tree.pos)))
    }
    val ret = Some(TptId("Boolean").withSym(BooleanClass))
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, paramss, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }

  private def valueClassHashCode(env: Env, tree: DefnClass): Unit = {
    val id = TermId("hashCode")
    val paramss = List(Nil)
    val ret = Some(TptId("Int").withSym(IntClass))
    val rhs = Some(TermStub())
    val method = DefnMethod(Mods(Nil), id, Nil, paramss, ret, rhs)
    scheduler(env, method.withPos(tree.pos))
  }
}

object Synthesizer {
  def apply(
      settings: Settings,
      reporter: Reporter,
      gensyms: Gensyms,
      symtab: Symtab,
      todo: Todo): Synthesizer = {
    new Synthesizer(settings, reporter, gensyms, symtab, todo)
  }
}
