// Copyright (c) 2005 - 2010, Clark & Parsia, LLC. <http://www.clarkparsia.com>

package com.clarkparsia.openrdf.query.builder;

import com.clarkparsia.openrdf.query.builder.Group;
import com.clarkparsia.openrdf.query.builder.GroupBuilder;
import com.clarkparsia.openrdf.query.builder.SupportsGroups;
import org.openrdf.query.algebra.TupleExpr;
import org.openrdf.query.algebra.Union;
import org.openrdf.query.parser.ParsedQuery;

/**
 * <p>Represents a unioned pair of groups in a query.</p>
 *
 * @author Michael Grove
 * @version 0.2.2
 * @since 0.2.2
 */
public class UnionGroup implements Group {
	private Group mLeft;
	private Group mRight;

	public UnionGroup(final Group theLeft, final Group theRight) {
		mLeft = theLeft;
		mRight = theRight;
	}

	public TupleExpr expr() {
		return new Union(mLeft.expr(), mRight.expr());
	}

	public boolean isOptional() {
		return false;
	}

	public void addChild(final Group theGroup) {
		if (mLeft == null) {
			mLeft = theGroup;
		}
		else if (mRight == null) {
			mRight = theGroup;
		}
		else {
			throw new IllegalArgumentException("Cannot set left or right arguments of union, both already set");
		}
	}
}