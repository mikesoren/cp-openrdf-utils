/*
 * Copyright (c) 2005-2013 Clark & Parsia, LLC. <http://www.clarkparsia.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.complexible.common.openrdf.query.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.openrdf.model.Literal;
import org.openrdf.model.Value;
import org.openrdf.model.impl.BooleanLiteralImpl;
import org.openrdf.query.algebra.And;
import org.openrdf.query.algebra.BinaryTupleOperator;
import org.openrdf.query.algebra.Bound;
import org.openrdf.query.algebra.Extension;
import org.openrdf.query.algebra.ExtensionElem;
import org.openrdf.query.algebra.Filter;
import org.openrdf.query.algebra.Join;
import org.openrdf.query.algebra.LeftJoin;
import org.openrdf.query.algebra.MultiProjection;
import org.openrdf.query.algebra.Or;
import org.openrdf.query.algebra.Projection;
import org.openrdf.query.algebra.ProjectionElem;
import org.openrdf.query.algebra.ProjectionElemList;
import org.openrdf.query.algebra.SameTerm;
import org.openrdf.query.algebra.SingletonSet;
import org.openrdf.query.algebra.StatementPattern;
import org.openrdf.query.algebra.StatementPattern.Scope;
import org.openrdf.query.algebra.TupleExpr;
import org.openrdf.query.algebra.UnaryTupleOperator;
import org.openrdf.query.algebra.Union;
import org.openrdf.query.algebra.ValueConstant;
import org.openrdf.query.algebra.ValueExpr;
import org.openrdf.query.algebra.Var;
import org.openrdf.query.algebra.helpers.QueryModelVisitorBase;

/**
 * <p>Implementation of a Algebra visitor that will re-write describe queries that are normally generated by Sesame to a far simpler form which is easier to evaluate.</p>
 *
 * @author Michael Grove
 * @since 0.3
 * @version 0.3
*/
public final class DescribeRewriter extends QueryModelVisitorBase<Exception> {
	private boolean mNamedGraphs;
	private Collection<String> mVars = new HashSet<String>();
	private Collection<Value> mValues = new HashSet<Value>();

	public DescribeRewriter(boolean theNamedGraphs) {
	    mNamedGraphs = theNamedGraphs;
    }

	/**
	 * @inheritDoc
	 */
	@Override
	public void meet(final Filter theFilter) throws Exception {
		super.meet(theFilter);

		rewriteUnary(theFilter);

		theFilter.visit(new ConstantVisitor());

		if (theFilter.getCondition() instanceof ValueConstant
			&& ((ValueConstant)theFilter.getCondition()).getValue().equals(BooleanLiteralImpl.TRUE)) {

			theFilter.replaceWith(theFilter.getArg());
		}
	}

	/**
	 * @inheritDoc
	 */
	@Override
	protected void meetUnaryTupleOperator(final UnaryTupleOperator theUnaryTupleOperator) throws Exception {
		super.meetUnaryTupleOperator(theUnaryTupleOperator);
		rewriteUnary(theUnaryTupleOperator);
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void meet(final SameTerm theSameTerm) throws Exception {
		super.meet(theSameTerm);

		boolean remove = false;

		if (theSameTerm.getLeftArg() instanceof Var && DescribeVisitor.isDescribeName(((Var)theSameTerm.getLeftArg()).getName())) {
			if (theSameTerm.getRightArg() instanceof Var) {
				mVars.add(((Var)theSameTerm.getRightArg()).getName());
			}
			else if (theSameTerm.getRightArg() instanceof ValueConstant) {
				mValues.add(((ValueConstant)theSameTerm.getRightArg()).getValue());
			}

			remove = true;
		}

		if (theSameTerm.getRightArg() instanceof Var && DescribeVisitor.isDescribeName(((Var)theSameTerm.getRightArg()).getName())) {
			if (theSameTerm.getLeftArg() instanceof Var) {
				mVars.add(((Var)theSameTerm.getLeftArg()).getName());
			}
			else if (theSameTerm.getLeftArg() instanceof ValueConstant) {
				mValues.add(((ValueConstant)theSameTerm.getLeftArg()).getValue());
			}

			remove = true;
		}

		if (remove) {
			theSameTerm.replaceWith(new ValueConstant(BooleanLiteralImpl.TRUE));
		}
	}
	
	private void addPattern(List<StatementPattern> thePatterns, Value theSubjectValue, String s, String p, String o, MultiProjection theProj) {
		ProjectionElemList pel = new ProjectionElemList();
		pel.addElement(new ProjectionElem(s, "subject"));
		pel.addElement(new ProjectionElem(p, "predicate"));
		pel.addElement(new ProjectionElem(o, "object"));
		if (mNamedGraphs) {
			pel.addElement(new ProjectionElem("context"));
		}

		thePatterns.add(new StatementPattern(new Var(s, theSubjectValue), new Var(p), new Var(o)));

		theProj.addProjection(pel);
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void meet(final Projection theProj) throws Exception {
		super.meet(theProj);

		// probably not a describe, so don't muck with the query algebra
		if (mValues.isEmpty() && mVars.isEmpty()) {
			return;
		}

		// TODO: scoping!
		MultiProjection aNewProj = new MultiProjection();
		Extension aExt = null;
		List<StatementPattern> aNewPatterns = new ArrayList<StatementPattern>();

		int count = 0;
		for (String aVar : mVars) {
			String s = aVar;
			String p = "proj" + (count++);
			String o = "proj" + (count++);
			
			addPattern(aNewPatterns, null, s, p, o, aNewProj);
		}

		for (Value aVal : mValues) {
			String s = "proj" + (count++);
			String p = "proj" + (count++);
			String o = "proj" + (count++);

			addPattern(aNewPatterns, aVal, s, p, o, aNewProj);

			if (aExt == null) {
				aExt = new Extension();
			}
			aExt.addElement(new ExtensionElem(new ValueConstant(aVal), s));
		}

		TupleExpr aExpr = theProj.getArg();
		if (!aNewPatterns.isEmpty()) {
			TupleExpr aNewPatternExpr = addGraphPattern(aNewPatterns.get(0));
			for (int i = 1; i < aNewPatterns.size(); i++) {
				aNewPatternExpr = new Union(aNewPatternExpr, addGraphPattern(aNewPatterns.get(i)));
			}
			
			aExpr = new Join(aExpr, aNewPatternExpr);
		}

		if (aExt != null) {
			aExt.setArg(aExpr);
			aExpr = aExt;
		}

		aNewProj.setArg(aExpr);

		theProj.replaceWith(aNewProj);
	}

	private TupleExpr addGraphPattern(StatementPattern thePattern) {
		if (mNamedGraphs) {
			StatementPattern aGraphPattern = thePattern.clone();
			aGraphPattern.setContextVar(new Var("context"));
			aGraphPattern.setScope(Scope.NAMED_CONTEXTS);
			return new Union(thePattern, aGraphPattern);
		}
		else {
			return thePattern;
		}
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void meet(final Join theJoin) throws Exception {
		super.meet(theJoin);
		rewriteBinary(theJoin);
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void meet(final Union theUnion) throws Exception {
		super.meet(theUnion);
		rewriteBinary(theUnion);
	}

	@Override
	public void meet(final StatementPattern thePattern) throws Exception {
		super.meet(thePattern);
		if (isDescribeOnlyPattern(thePattern)) {
			thePattern.replaceWith(new SingletonSet());
		}
	}

	private void rewriteBinary(BinaryTupleOperator theOp) {
		boolean removeLeft = false;
		boolean removeRight = false;

		if (isDescribeOnlyPattern(theOp.getLeftArg())) {
			removeLeft = true;
		}

		if (isDescribeOnlyPattern(theOp.getRightArg())) {
			removeRight = true;
		}

		if (removeLeft && removeRight) {
			theOp.replaceWith(new SingletonSet());
		}
		else if (removeLeft) {
			theOp.replaceWith(theOp.getRightArg());
		}
		else if (removeRight) {
			theOp.replaceWith(theOp.getLeftArg());
		}
	}

	private void rewriteUnary(UnaryTupleOperator theOp) {
		if (isDescribeOnlyPattern(theOp.getArg())) {
			theOp.getArg().replaceWith(new SingletonSet());
		}
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void meet(final LeftJoin theLeftJoin) throws Exception {
		super.meet(theLeftJoin);

		boolean removeLeft = false;
		boolean removeRight = false;

		if (isDescribeOnlyPattern(theLeftJoin.getLeftArg())) {
			removeLeft = true;
		}

		if (isDescribeOnlyPattern(theLeftJoin.getRightArg())) {
			removeRight = true;
		}

		if (removeLeft && removeRight) {
			theLeftJoin.replaceWith(new SingletonSet());
		}
		else if (removeLeft) {
			if (theLeftJoin.getCondition() != null) {
				theLeftJoin.replaceWith(new Filter(theLeftJoin.getRightArg(), theLeftJoin.getCondition()));
			}
			else {
				theLeftJoin.replaceWith(theLeftJoin.getRightArg());
			}
		}
		else if (removeRight) {
			if (theLeftJoin.getCondition() != null) {
				theLeftJoin.replaceWith(new Filter(theLeftJoin.getLeftArg(), theLeftJoin.getCondition()));
			}
			else {
				theLeftJoin.replaceWith(theLeftJoin.getLeftArg());
			}
		}
	}

	private boolean isDescribeOnlyPattern(final TupleExpr theExpr) {
		return theExpr instanceof StatementPattern
			   && (((StatementPattern)theExpr).getSubjectVar().getName().equals("-descr-subj")
                   || ((StatementPattern)theExpr).getSubjectVar().getName().equals("descrsubj"))
			   && (((StatementPattern)theExpr).getPredicateVar().getName().equals("-descr-pred")
                   || ((StatementPattern)theExpr).getPredicateVar().getName().equals("descrpred"))
			   && (((StatementPattern)theExpr).getObjectVar().getName().equals("-descr-obj")
                || ((StatementPattern)theExpr).getObjectVar().getName().equals("descrobj"));
	}

	/**
	 * Adapted from the Sesame visitor of the same name in the ConstantOptimizer.  Primary change is that it does not require an evaluation strategy to complete the work.
	 */
	private static class ConstantVisitor extends QueryModelVisitorBase<Exception> {

		@Override
		public void meet(Or or) throws Exception {
			or.visitChildren(this);

			if (isConstant(or.getLeftArg()) && isConstant(or.getRightArg())) {
				boolean value = isTrue(or.getLeftArg()) && isTrue(or.getRightArg());
				or.replaceWith(new ValueConstant(BooleanLiteralImpl.valueOf(value)));
			}
			else if (isConstant(or.getLeftArg())) {
				boolean leftIsTrue = isTrue(or.getLeftArg());
				if (leftIsTrue) {
					or.replaceWith(new ValueConstant(BooleanLiteralImpl.TRUE));
				}
				else {
					or.replaceWith(or.getRightArg());
				}
			}
			else if (isConstant(or.getRightArg())) {
				boolean rightIsTrue = isTrue(or.getRightArg());
				if (rightIsTrue) {
					or.replaceWith(new ValueConstant(BooleanLiteralImpl.TRUE));
				}
				else {
					or.replaceWith(or.getLeftArg());
				}
			}
		}

		@Override
		public void meet(And and) throws Exception {
			and.visitChildren(this);

				if (isConstant(and.getLeftArg()) && isConstant(and.getRightArg())) {
					boolean value = isTrue(and.getLeftArg()) && isTrue(and.getRightArg());
					and.replaceWith(new ValueConstant(BooleanLiteralImpl.valueOf(value)));
				}
				else if (isConstant(and.getLeftArg())) {
					boolean leftIsTrue = isTrue(and.getLeftArg());
					if (leftIsTrue) {
						and.replaceWith(and.getRightArg());
					}
					else {
						and.replaceWith(new ValueConstant(BooleanLiteralImpl.FALSE));
					}
				}
				else if (isConstant(and.getRightArg())) {
					boolean rightIsTrue = isTrue(and.getRightArg());
					if (rightIsTrue) {
						and.replaceWith(and.getLeftArg());
					}
					else {
						and.replaceWith(new ValueConstant(BooleanLiteralImpl.FALSE));
					}
				}
		}

		@Override
		public void meet(Bound bound) throws Exception {
			super.meet(bound);

			if (bound.getArg().hasValue()) {
				// variable is always bound
				bound.replaceWith(new ValueConstant(BooleanLiteralImpl.TRUE));
			}
		}

		private boolean isTrue(final ValueExpr theValue) {
			return ((ValueConstant)theValue).getValue() instanceof Literal && ((Literal)((ValueConstant)theValue).getValue()).booleanValue();
		}

		private boolean isTrue(final Value theValue) {
			return theValue instanceof Literal && ((Literal)theValue).booleanValue();
		}

		private boolean isConstant(ValueExpr expr) {
			return expr instanceof ValueConstant || expr instanceof Var && ((Var) expr).hasValue();
		}
	}

	public static class Clean extends QueryModelVisitorBase<Exception> {

		@Override
		protected void meetBinaryTupleOperator(final BinaryTupleOperator theBinaryTupleOperator) throws Exception {
			super.meetBinaryTupleOperator(theBinaryTupleOperator);

			boolean removeLeft = theBinaryTupleOperator.getLeftArg() instanceof SingletonSet;
			boolean removeRight = theBinaryTupleOperator.getRightArg() instanceof SingletonSet;

			if (removeLeft && removeRight) {
				theBinaryTupleOperator.replaceWith(new SingletonSet());
			}
			else if (removeLeft) {
				theBinaryTupleOperator.replaceWith(theBinaryTupleOperator.getRightArg());
			}
			else if (removeRight) {
				theBinaryTupleOperator.replaceWith(theBinaryTupleOperator.getLeftArg());
			}
		}
	}
}