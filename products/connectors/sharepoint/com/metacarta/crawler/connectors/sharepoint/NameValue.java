/* $Id$ */

/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
* 
* http://www.apache.org/licenses/LICENSE-2.0
* 
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.metacarta.crawler.connectors.sharepoint;

/** Helper class which returns pretty names plus actual stuff to append to the sharepoint path */
public class NameValue
{
	public static final String _rcsid = "@(#)$Id$";

	// The pretty name
	protected String prettyName;
	// The real value
	protected String realValue;
	
	/** Instantiate */
	public NameValue(String realValue, String prettyName)
	{
		this.realValue = realValue;
		this.prettyName = prettyName;
	}
	
	/** Get the real value */
	public String getValue()
	{
		return realValue;
	}
	
	/** Get the pretty name */
	public String getPrettyName()
	{
		return prettyName;
	}
}
