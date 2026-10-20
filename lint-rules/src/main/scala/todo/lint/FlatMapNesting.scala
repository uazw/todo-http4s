package todo.lint

import scala.meta.*
import scalafix.lint.LintSeverity
import scalafix.v1.*

/** Counts explicit flatMap callbacks in source, before for-comprehension desugaring. */
class FlatMapNesting extends SyntacticRule("FlatMapNesting"):
  override def fix(using doc: SyntacticDocument): Patch =
    FlatMapNesting
      .violations(doc.tree)
      .map: (position, depth) =>
        Patch.lint(
          Diagnostic(
            "tooDeep",
            s"flatMap callback nesting is $depth (maximum ${FlatMapNesting.maxDepth}); use a for comprehension or extract a named helper.",
            position,
            severity = LintSeverity.Error
          )
        )
      .asPatch

object FlatMapNesting:
  val maxDepth = 2

  private def targetsFlatMap(term: Term): Boolean = term match
    case Term.Select(_, Term.Name("flatMap")) => true
    case Term.Apply(fun, _)                   => targetsFlatMap(fun)
    case Term.ApplyType(fun, _)               => targetsFlatMap(fun)
    case _                                    => false

  private def isCallback(term: Term): Boolean = term match
    case _: Term.Function | _: Term.PartialFunction | _: Term.AnonymousFunction => true
    case Term.Block(stats)                                                      =>
      stats.lastOption.exists:
        case term: Term => isCallback(term)
        case _          => false
    case Term.Assign(_, value) => isCallback(value)
    case _                     => false

  def violations(tree: Tree): List[(Position, Int)] =
    def argument(term: Term, depth: Int): List[(Position, Int)] =
      val next = if isCallback(term) then depth + 1 else depth
      val current = if next > maxDepth && isCallback(term) then List(term.pos -> next) else Nil
      current ++ loop(term, next)

    def loop(tree: Tree, depth: Int): List[(Position, Int)] = tree match
      case Term.Apply(fun, args) if targetsFlatMap(fun) =>
        loop(fun, depth) ++ args.flatMap(argument(_, depth))
      case Term.ApplyInfix(lhs, Term.Name("flatMap"), _, args) =>
        loop(lhs, depth) ++ args.flatMap(argument(_, depth))
      // Named helpers are independent flows, even when defined inside a callback.
      case definition: Defn.Def => definition.children.flatMap(loop(_, 0))
      case _                    => tree.children.flatMap(loop(_, depth))

    loop(tree, 0)
