/*******************************************************************************
 * UW SPF - The University of Washington Semantic Parsing Framework
 * <p>
 * Copyright (C) 2013 Yoav Artzi
 * <p>
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 ******************************************************************************/
package edu.uw.cs.lil.tiny.parser.ccg.rules.lambda.typeshifting.basic;

import edu.uw.cs.lil.tiny.ccg.categories.Category;
import edu.uw.cs.lil.tiny.ccg.categories.syntax.ComplexSyntax;
import edu.uw.cs.lil.tiny.ccg.categories.syntax.Slash;
import edu.uw.cs.lil.tiny.ccg.categories.syntax.Syntax;
import edu.uw.cs.lil.tiny.mr.lambda.LogicalExpression;

/**
 * PP => N\N
 * 
 * @author Yoav Artzi
 */
public class PrepositionTypeShifting extends
		AbstractTypeShiftingFunctionForThreading {
	private static final Syntax	N_BS_N_SYNTAX	= new ComplexSyntax(Syntax.N,
														Syntax.N,
														Slash.BACKWARD);
	
	@Override
	public boolean equals(Object obj) {
		return obj instanceof PrepositionTypeShifting;
	}
	
	@Override
	public int hashCode() {
		return PrepositionTypeShifting.class.getName().hashCode();
	}
	
	@Override
	public Category<LogicalExpression> typeRaise(
			Category<LogicalExpression> category) {
		if (category.getSyntax().equals(Syntax.PP)) {
			final LogicalExpression raisedSemantics = typeRaiseSemantics(category
					.getSem());
			if (raisedSemantics != null) {
				return Category.create(N_BS_N_SYNTAX, raisedSemantics);
			}
		}
		return null;
	}
	
}