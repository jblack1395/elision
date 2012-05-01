/*======================================================================
 *       _ _     _
 *   ___| (_)___(_) ___  _ __
 *  / _ \ | / __| |/ _ \| '_ \
 * |  __/ | \__ \ | (_) | | | |
 *  \___|_|_|___/_|\___/|_| |_|
 * The Elision Term Rewriter
 * 
 * Copyright (c) 2012 by Stacy Prowell (sprowell@gmail.com)
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met: 
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer. 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution. 
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
======================================================================*/
package sjp.elision.core

import scala.collection.immutable.HashMap
import scala.collection.mutable.ListBuffer
import sjp.elision.ElisionException

/**
 * An incorrect argument list was supplied to an operator.
 * @param msg	The human-readable message describing the problem.
 */
class ArgumentListException(msg: String) extends ElisionException(msg)

/**
 * Encapsulate an operator.
 * 
 * ==Structure and Syntax==
 * An operator, by itself, is simply a name for a function that maps from some
 * domain to some codomain.  An operator appears as a simple symbol whose type
 * is designated as OPTYPE (to force looking up the operator).  Otherwise
 * operators are detected when they are applied to some argument list.
 * 
 * ==Type==
 * The type of an operator is taken from its definition.
 * 
 * ==Equality and Matching==
 * Operators are equal iff their operator definitions are equal.  They match
 * if their operator definitions match.
 * 
 * @param opdef	The operator definition.
 */
abstract class Operator(sfh: SpecialFormHolder,
    val name: String, val typ: BasicAtom, definition: AtomSeq)
    extends SpecialForm(sfh.tag, sfh.content) with Applicable
    
object CaseOperator {
  val tag = Literal(Symbol("case"))
  
  def apply(sfh: SpecialFormHolder): CaseOperator = {
    val bh = sfh.requireBindings
    bh.check(Map("name"->true, "cases"->true, "type"->false))
    val name = bh.fetchAs[SymbolLiteral]("name").value.name
    val cases = bh.fetchAs[AtomSeq]("cases")
    val typ = bh.fetchAs[BasicAtom]("type", Some(ANY))
    return new CaseOperator(sfh, name, typ, cases)
  }
  
  def apply(name: String, typ: BasicAtom, cases: AtomSeq): CaseOperator = {
    val nameS = Literal(Symbol(name))
    val binds = Bindings() + ("name"->nameS) + ("cases"->cases) + ("type"->typ)
    val sfh = new SpecialFormHolder(tag, binds)
    return new CaseOperator(sfh, name, typ, cases)
  }
  
  def unapply(co: CaseOperator) = Some((co.name, co.theType, co.cases))
}

class OperatorRef(val op: Operator) extends BasicAtom with Applicable {
  val depth = 0
  val deBruijnIndex = 0
  val constantPool = None
  val isTerm = true
  val isConstant = true
  val theType = OPREF
  def doApply(atom: BasicAtom) = op.doApply(atom)
  def toParseString = toESymbol(op.name) + ":OPREF"
  def rewrite(binds: Bindings) = (this, false)
  def tryMatchWithoutTypes(subject: BasicAtom, binds: Bindings, hints: Option[Any]) =
    if (subject == this) Match(binds)
    else subject match {
      case OperatorRef(oop) if (oop == op) => Match(binds)
      case oop: Operator if (oop == op) => Match(binds)
      case _ => Fail("Operator reference does not match subject.", this, subject)
    }
}

object OperatorRef {
  def unapply(ref: OperatorRef) = Some(ref.op)
  def apply(op: Operator) = new OperatorRef(op)
}

class CaseOperator private (sfh: SpecialFormHolder,
    name: String, typ: BasicAtom, val cases: AtomSeq)
		extends Operator(sfh, name, typ, cases) {
  override val theType = typ
  
  def doApply(args: BasicAtom) = {
    // Traverse the list of cases and try to find a case that the arguments
    // match.  Every case should be a rewritable, an applicable, or an atom.
    // If a rewritable, apply it and if it succeeds, choose the result.
    // If an applicable, apply it.  If any other atom, choose that atom.
    var result: Option[BasicAtom] = None
    cases.exists { _ match {
      case rew: Rewriter =>
        val pair = rew.doRewrite(args)
        result = Some(pair._1)
        pair._2
      case app: Applicable =>
        result = Some(app.doApply(args))
        true
      case atom =>
        result = Some(atom)
        true
    }}
    // If nothing worked, then we need to generate an error since the operator
    // was incorrectly applied.
    if (result.isEmpty)
      throw new ArgumentListException("Applied the operator " +
          toESymbol(name) + " to an incorrect argument list: " +
          args.toParseString)
    // If the result turned out to be ANY, then just construct a simple
    // apply for this operator.
    result.get match {
      case ANY => SimpleApply(this, args)
      case other => other
    }
  }
}

object SymbolicOperator {
  val tag = Literal(Symbol("operator"))
  
  def apply(sfh: SpecialFormHolder): SymbolicOperator = {
    val bh = sfh.requireBindings
    bh.check(Map("name"->true, "params"->true, "type"->false))
    val name = bh.fetchAs[SymbolLiteral]("name").value.name
    val params = bh.fetchAs[AtomSeq]("params")
    val typ = bh.fetchAs[BasicAtom]("type", Some(ANY))
    return new SymbolicOperator(sfh, name, typ, params)
  }
  
  def apply(name: String, typ: BasicAtom, params: AtomSeq): SymbolicOperator = {
    val nameS = Literal(Symbol(name))
    val binds = Bindings() + ("name"->nameS) + ("params"->params) + ("type"->typ)
    val sfh = new SpecialFormHolder(tag, binds)
    return new SymbolicOperator(sfh, name, typ, params)
  }
  
  def unapply(so: SymbolicOperator) = Some((so.name, so.theType, so.params))
}

class SymbolicOperator private (sfh: SpecialFormHolder,
    name: String, typ: BasicAtom, params: AtomSeq)
		extends PseudoOperator(sfh, name, typ, params) {
	/**
   * The type of an operator is a mapping from the operator domain to the
   * operator codomain.
   */
	override val theType = PseudoOperator.makeOperatorType(params, typ)
}

object PseudoOperator {
  val tag = Literal(Symbol(""))
  
  def apply(name: String, typ: BasicAtom, params: AtomSeq): PseudoOperator = {
    val nameS = Literal(Symbol(name))
    val binds = Bindings() + ("name"->nameS) + ("params"->params) + ("type"->typ)
    val sfh = new SpecialFormHolder(tag, binds)
    return new PseudoOperator(sfh, name, typ, params)
  }
  
  def unapply(so: PseudoOperator) = Some((so.name, so.theType, so.params))

  val MAP = PseudoOperator("MAP", ANY, AtomSeq(NoProps, 'domain, 'codomain))
  val xx = PseudoOperator("xx", ANY, AtomSeq(Associative(true), 'x, 'y))
  
  def makeOperatorType(params: AtomSeq, typ: BasicAtom) =
  params.length match {
    case 0 => MAP(NONE, typ)
    case 1 => MAP(params(0).theType, typ)
    case _ => MAP(xx(params.map(_.theType):_*), typ)
  }
}

class PseudoOperator protected (sfh: SpecialFormHolder,
    name: String, typ: BasicAtom, val params: AtomSeq)
		extends Operator(sfh, name, typ, params) {
	println("MAKING an instance of: " + name)
	(new Exception()).printStackTrace
	override val theType: BasicAtom = ANY
  
  /** The native handler, if one is declared. */
  protected[core]
  var handler: Option[(PseudoOperator,AtomSeq,Bindings) => BasicAtom] = None
	
  def apply(args: BasicAtom*): BasicAtom = {
    // Make an atom list from the arguments.
    val seq = AtomSeq(NoProps, args:_*)
    doApply(seq, false)
  }
  
  def doApply(arg: BasicAtom) = arg match {
    case as: AtomSeq => doApply(as, false)
    case _ => SimpleApply(this, false)
  }
  
  def doApply(args: AtomSeq, bypass: Boolean): BasicAtom = {
    // There are special cases to handle here.  First, if the argument list
    // is empty, but there is an identity, return it.
    if (args.length == 0) {
      params.identity match {
        case Some(ident) =>
          // Return the identity.
          return ident
        case None =>
          // No identity.  Proceed with caution.
      }
    }
    
    // If the argument list is associative and we have a single element, then
    // that element must match the type of the operator, and we return it.
    if (args.length == 1) {
      if (params.associative) {
        // Get the atom.
        val atom = args(0)
        // Match the type of the atom against the type of the parameters.
        val param = params(0)
        param.tryMatch(atom) match {
          case Fail(reason, index) =>
            // The argument is invalid.  Reject!
			      throw new ArgumentListException("Incorrect argument for operator " +
			          toESymbol(name) + " at position 0: " + atom.toParseString)
          case mat: Match =>
            // The argument matches.
            return atom
          case many: Many =>
            // The argument matches.
            return atom
        }
      }
    }
    
    // If the operator is associative, we pad the parameter list to get faster
    // matching.  Otherwise we just match as-is.  In any case, when we are done
    // we can just use the sequence matcher.
    val newparams = if (params.associative) {
      var newatoms: OmitSeq[BasicAtom] = EmptySeq
      val atom = params(0)
      for (index <- 0 until args.length) {
        var param = Variable(atom.theType, ""+index)
        newatoms = param +: newatoms
      } // Build new parameter list.
      newatoms
    } else {
      params.atoms
    }
    
    // Handle actual operator application.
    def handleApply(binds: Bindings): BasicAtom = {
      // Re-package the arguments with the correct properties.
      val newargs = AtomSeq(params.props, args.atoms)
      // See if we are bypassing the native handler.
      if (!bypass) {
        // Run any native handler.
        if (handler.isDefined) return handler.get(this, newargs, binds)
      }
      // No native handler.
      return OpApply(this, newargs, binds)
    }
    
    // We've run out of special cases to handle.  Now just try to match the
    // arguments against the parameters.
    SequenceMatcher.tryMatch(newparams, args) match {
      case Fail(reason, index) =>
	      throw new ArgumentListException("Incorrect argument for operator " +
	          toESymbol(name) + " at position " + index + ": " +
	          args(0).toParseString)
      case Match(binds) =>
        // The argument list matches.
        return handleApply(binds)
      case Many(iter) =>
        // The argument list matches.
        return handleApply(iter.next)
    }
  }
}
