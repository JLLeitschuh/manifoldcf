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
package com.metacarta.crawler;

import java.io.*;
import com.metacarta.core.interfaces.*;
import com.metacarta.crawler.interfaces.*;
import com.metacarta.crawler.system.*;
import java.util.*;

/** This class converts a job name into a job identifier.
* Warning: Since multiple jobs can have the same name, a list of job identifiers will be returned.
*/
public class FindJob
{
	public static final String _rcsid = "@(#)$Id$";

	private FindJob()
	{
	}

	public static void main(String[] args)
	{
		if (args.length != 1)
		{
			System.err.println("Usage: FindJob <job_name>");
			System.exit(1);
		}

		String jobName = args[0];


		try
		{
		        Metacarta.initializeEnvironment();
			IThreadContext tc = ThreadContextFactory.make();
			IJobManager jobManager = JobManagerFactory.make(tc);
			IJobDescription[] jobs = jobManager.getAllJobs();
			int i = 0;
			while (i < jobs.length)
			{
				IJobDescription jobDesc = jobs[i++];
				if (jobDesc.getDescription().equals(jobName))
					UTF8Stdout.println(jobDesc.getID().toString());
			}
			System.err.println("Job search done");
		}
		catch (Exception e)
		{
			e.printStackTrace();
			System.exit(2);
		}
	}
		
}
