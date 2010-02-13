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
package com.metacarta.crawler.connectors.livelink;

import com.metacarta.license.LicenseFileService;
import com.metacarta.license.LicenseFile;
import com.metacarta.license.MetaCartaLicenseFileConstants;
import java.lang.reflect.*;

/* singleton class to manage the MetaCarta license file.  Call
 * verify() to figure out if the livelink connector is enabled.
 * TBD: replace this with a more generic interface POST FREEZE.
 */

public class LivelinkLicense {
    public static final String _rcsid = "@(#)$Id$";
    

    private static LivelinkLicense _instance = null;

    private static synchronized void createInstance() {
	if (_instance == null) {
		_instance = new LivelinkLicense();
	}
    }   

    public static LivelinkLicense getInstance() {
	if (_instance == null) {
	    createInstance();
	}
	return _instance;
    }

    private static ClassLoader findClassLoader()
    {
	ClassLoader rval = ClassLoader.getSystemClassLoader();
	return rval;
	/*
	while (true)
	    {
		ClassLoader parent = rval.getParent();
		if (parent == null)
		    return rval;
		rval = parent;
	    }
	*/
    }

    private LicenseFileService lfs = null;
   
    // constructor
    private LivelinkLicense() {
	ClassLoader cl = findClassLoader();
	try
	    {
		Class licenseFileServiceClass = cl.loadClass("com.metacarta.license.LicenseFileService");
		Class constantsClass = cl.loadClass("com.metacarta.license.MetaCartaLicenseFileConstants");
		String connector_prefix = (String)constantsClass.getDeclaredField("LIVELINK_CONNECTOR_PREFIX").get(null);
		Constructor c = licenseFileServiceClass.getConstructor(new Class[]{String.class,String.class});
		lfs = (LicenseFileService)c.newInstance(new Object[]{"/var/lib/metacarta/license",connector_prefix});
	    }
	catch (Exception e)
	    {
		throw new Error("Can't invoke license manager",e);
	    }
					   // lfs = new LicenseFileService("/var/lib/metacarta/license", 
					   // MetaCartaLicenseFileConstants.LIVELINK_CONNECTOR_PREFIX);
    }
    
    public LicenseFile.Error verify() {
	return lfs.verify();
    }
}

