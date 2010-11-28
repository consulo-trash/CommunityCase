/*******************************************************************************
 * Copyright (c) 2002, 2008 eclipse-ccase.sourceforge.net team and others
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *   Mikael Petterson
 *   Vincent Latombe
 *******************************************************************************/

package net.sourceforge.clearcase.commandline.output.strategy;

import java.util.regex.Matcher;

import net.sourceforge.clearcase.ClearCase;
import net.sourceforge.clearcase.ClearCaseElementState;
import net.sourceforge.clearcase.ClearCaseException;

/**
 * TODO Provide description for ElementAlreadyCheckedout.
 */
public class ElementAlreadyCheckedout extends AbstractMatcherStrategy {

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * net.sourceforge.clearcase.commandline.output.strategy.GenericStrategy
	 * #getPattern()
	 */
	@Override
	protected String getPattern() {
		return "cleartool: Error: Element .* is already checked out to view .*$";
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * net.sourceforge.clearcase.commandline.output.strategy.GenericStrategy
	 * #getResult()
	 */
	@Override
	protected ClearCaseElementState getResult(String output, Matcher matcher)
			throws ClearCaseException {
		throw new ClearCaseException(ClearCase.ERROR_ALREADY_CHECKED_OUT);
	}

}