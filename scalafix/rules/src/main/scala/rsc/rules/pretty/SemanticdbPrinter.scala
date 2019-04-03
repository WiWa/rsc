// Copyright (c) 2017-2019 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
// NOTE: This file has been partially copy/pasted from scalameta/scalameta.
package rsc.rules.pretty

import rsc.lexis.scala._
import rsc.pretty._
import rsc.rules._
import rsc.rules.semantics._
import scala.collection.mutable
import scala.meta.internal.{semanticdb => s}
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Scala.{Descriptor => d}
import scala.meta.internal.semanticdb.Scala.{Names => n}
import scalafix.internal.util.TypeExtractors
import scalafix.internal.v0._

class SemanticdbPrinter(
    env: Env,
    addedImportsScope: AddedImportsScope,
    symbols: DocumentSymbols,
    config: RscCompatConfig
) extends Printer {

  def pprint(tpe: s.Type): Unit = {
    def prefix(tpe: s.Type): Unit = {
      tpe match {
        case s.TypeRef(pre, sym, args) =>
          if (sym.startsWith("scala/Function") &&
              args.exists(_.isInstanceOf[s.ByNameType])) {
            var params :+ ret = args
            if (params.length != 1) str("(")
            rep(params, ", ") { param =>
              // FIXME: https://github.com/twitter/rsc/issues/142
              str("(")
              normal(param)
              str(")")
            }
            if (params.length != 1) str(")")
            str(" => ")
            normal(ret)
          } else {
            if (config.better) {
              val tupleRegex22 = "scala/Tuple([2-9]|1[0-9]|21|22)#".r
              tupleRegex22.findPrefixOf(sym) match {
                case Some(_) =>
                  rep("(", args, ", ", ")")(normal)
                  return
                case _ => ()
              }
              def isFunc(sym1: String): Boolean =
                "scala/Function([0-9]|1[0-9]|21|22)#".r.findPrefixOf(sym1).isDefined

              if (isFunc(sym)) {
                if (args.size > 2) {
                  rep("(", args.init, ", ", ")")(normal)
                  pprint(" => ")
                  pprint(args.last)
                  return
                } else if (args.size == 1) {
                  pprint("() => ")
                  pprint(args.last)
                  return
                } else if (args.headOption
                             .collect {
                               case s.TypeRef(_, sym1, _) => isFunc(sym1)
                             }
                             .getOrElse(false)) {
                  rep("(", args.headOption, ", ", ")")(normal)
                  pprint(" => ")
                  pprint(args.last)
                  return
                } else {
                  pprint(args.head)
                  pprint(" => ")
                  pprint(args.last)
                  return
                }
              }
            }
            // TODO: At the moment, we return None for local symbols, since they don't have a desc.
            // The logic to improve on this is left for future work.
            val name = sym.desc match {
              case d.Term(value) => Some(n.TermName(value))
              case d.Type(value) => Some(n.TypeName(value))
              case d.Package(value) => Some(n.TermName(value))
              case d.Parameter(value) => Some(n.TermName(value))
              case d.TypeParameter(value) => Some(n.TypeName(value))
              case other => None
            }
            def printPrettyPrefix: Unit = {
              val prettyPre = if (pre == s.NoType) sym.trivialPrefix(env) else pre
              prettyPre match {
                case _: s.SingleType | _: s.ThisType | _: s.SuperType =>
                  prefix(prettyPre)
                  str(".")
                case s.NoType =>
                  ()
                case _ =>
                  prefix(prettyPre)
                  str("#")
              }
            }
            if (config.better) {
              name.map(fullEnv.lookup) match {
                case Some(x) if !symbols.equivalent(x, sym) =>
                  if (x.isEmpty && pre == s.NoType) {
                    addedImportsScope.addImport(sym)
                  } else {
                    printPrettyPrefix
                  }
                case _ =>
                  ()
              }
            } else {
              printPrettyPrefix
            }
            pprint(sym)
            rep("[", args, ", ", "]")(normal)
          }
        case s.SingleType(pre, sym) =>
          if (config.better && symbols.equivalent(fullEnv.lookup(sym.desc.name), sym)) {
            str(sym.desc.value)
          } else if (config.better && fullEnv.lookup(sym.desc.name).isEmpty) {
            addedImportsScope.addImport(sym)
            str(sym.desc.value)
          } else {
            val prettyPre = if (pre == s.NoType) sym.trivialPrefix(env) else pre
            opt(prettyPre, ".")(prefix)
            pprint(sym)
          }
        case s.ThisType(sym) =>
          opt(sym, ".")(pprint)
          str("this")
        case s.WithType(types) =>
          val filteredTypes = if (config.better) {
            types.filter {
              case s.TypeRef(_, "scala/AnyRef#", _) | s.TypeRef(_, "java/lang/Object#", _) => false
              case _ => true
            } match {
              case Nil => types
              case ts => ts
            }
          } else {
            types
          }

          rep(filteredTypes, " with ") { tpe =>
            // FIXME: https://github.com/twitter/rsc/issues/142
            val needsParens = tpe.isInstanceOf[s.ExistentialType]
            if (needsParens) str("(")
            normal(tpe)
            if (needsParens) str(")")
          }
        case s.StructuralType(utpe, decls) =>
          decls.infos.foreach(symbols.append)
          opt(utpe)(normal)
          if (decls.infos.nonEmpty) {
            rep(" { ", decls.infos, "; ", " }")(pprint)
          } else {
            utpe match {
              case s.WithType(tpes) if tpes.length > 1 => ()
              case _ => str(" {}")
            }
          }
        case s.AnnotatedType(anns, utpe) =>
          opt(utpe)(normal)
          anns.toList match {
            case s.Annotation(s.NoType) :: Nil =>
              ()
            case _ =>
              rep(" ", anns, " ", "")(pprint)
          }
        case s.ExistentialType(utpe, decls) =>
          val infos = decls.infos
          infos.foreach(symbols.append)
          if (infos.length == 1) {
            infos.head.signature match {
              case s.TypeSignature(_, TypeExtractors.Nothing(), TypeExtractors.Any()) =>
                utpe match {
                  case s.TypeRef(s.NoType, sym1, typeArguments) if typeArguments.length == 1 =>
                    pprint(sym1)
                    str("[_]")
                    return
                }
            }
          }
          opt(utpe)(normal)
          rep(" forSome { ", infos, "; ", " }")(pprint)
        case s.UniversalType(tparams, utpe) =>
          // FIXME: https://github.com/twitter/rsc/issues/150
          str("({ type λ")
          tparams.infos.foreach(symbols.append)
          rep("[", tparams.infos, ", ", "] = ")(pprint)
          opt(utpe)(normal)
          str(" })#λ")
        case s.ByNameType(utpe) =>
          str("=> ")
          opt(utpe)(normal)
        case s.RepeatedType(utpe) =>
          opt(utpe)(normal)
          str("*")
        case _: s.SuperType | _: s.ConstantType | _: s.IntersectionType | _: s.UnionType |
            s.NoType =>
          val details = tpe.asMessage.toProtoString
          sys.error(s"unsupported type: $details")
      }
    }
    def normal(tpe: s.Type): Unit = {
      tpe match {
        case _: s.SingleType | _: s.ThisType | _: s.SuperType =>
          prefix(tpe)
          str(".type")
        case _ =>
          prefix(tpe)
      }
    }
    normal(tpe)
  }

  private val fullEnv = Env(env.scopes :+ addedImportsScope)

  private def pprint(sym: String): Unit = {
    val printableName = {
      val info = symbols.info(sym)
      info match {
        case Some(info) =>
          if (info.isPackageObject) {
            "package"
          } else {
            val displayName = info.displayName
            if (displayName == "") {
              sys.error(s"unsupported symbol: $sym")
            } else if (displayName == "_" || displayName.startsWith("?")) {
              gensymCache.getOrElseUpdate(sym, gensym("T"))
            } else {
              displayName
            }
          }
        case None =>
          if (sym.isGlobal) sym.desc.value
          else sym
      }
    }
    if (keywords.containsKey(printableName)) str("`")
    str(printableName)
    if (keywords.containsKey(printableName)) str("`")
  }

  private def pprint(info: s.SymbolInformation): Unit = {
    if (info.isMethod && info.displayName.endsWith("_=")) return
    symbols.append(info)
    rep(info.annotations, " ", " ")(pprint)
    if (info.isCovariant) str("+")
    if (info.isContravariant) str("-")
    if (info.isMethod && info.isVal) str("val ")
    else if (info.isMethod && info.isVar) str("var ")
    else if (info.isMethod) str("def ")
    else if (info.isType) str("type ")
    else if (info.isParameter) str("")
    else if (info.isTypeParameter) str("")
    else sys.error(s"unsupported info: ${info.toProtoString}")
    pprint(info.symbol)
    info.signature match {
      case s.MethodSignature(tparams, paramss, res) =>
        rep("[", tparams.infos, ", ", "]")(pprint)
        rep("(", paramss, ")(", ")") { params =>
          if (params.infos.exists(_.isImplicit)) str("implicit ")
          rep(params.infos, ", ")(pprint)
        }
        opt(": ", res)(pprint)
      case s.TypeSignature(tparams, lo, hi) =>
        rep("[", tparams.infos, ", ", "]")(pprint)
        if (lo != hi) {
          lo match {
            case s.TypeRef(s.NoType, "scala/Nothing#", Nil) => ()
            case lo => opt(" >: ", lo)(pprint)
          }
          hi match {
            case s.TypeRef(s.NoType, "scala/Any#", Nil) => ()
            case hi => opt(" <: ", hi)(pprint)
          }
        } else {
          val alias = lo
          opt(" = ", alias)(pprint)
        }
      case s.ValueSignature(tpe) =>
        str(": ")
        pprint(tpe)
      case other =>
        val details = other.asMessage.toProtoString
        sys.error(s"unsupported signature: $details")
    }
  }

  private def pprint(ann: s.Annotation): Unit = {
    str("@")
    ann.tpe match {
      case s.NoType =>
        sys.error(s"unsupported annotation: ${ann.toProtoString}")
      case tpe =>
        pprint(tpe)
    }
  }

  private val gensymCache = mutable.Map[String, String]()
  private object gensym {
    private val counters = mutable.Map[String, Int]()
    def apply(prefix: String): String = {
      val nextCounter = counters.getOrElse(prefix, 0) + 1
      counters(prefix) = nextCounter
      prefix + nextCounter
    }
  }
}
