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
package com.metacarta.agents.interfaces;

import com.metacarta.core.interfaces.*;
import com.metacarta.agents.interfaces.*;

import java.io.*;
import java.util.*;

/** This interface describes an instance of a connection between an engine that needs to output documents,
* and an output connector.
*
* Each instance of this interface is used in only one thread at a time.  Connection Pooling
* on these kinds of objects is performed by the factory which instantiates connector objects
* from symbolic names and config parameters, and is pooled by these parameters.  That is, a pooled connector
* handle is used only if all the connection parameters for the handle match.
*
* Implementers of this interface should provide a default constructor which has this signature:
*
* xxx();
*
* Connector instances are either configured or not.  If configured, they will persist in a pool, and be
* reused multiple times.  Certain methods of a connector may be called before the connector is
* configured.  This includes basically all methods that permit inspection of the connector's
* capabilities.  The complete list is:
*
*
* The purpose of the output connector is to allow documents to be sent to their final destination (as far as
* Connector Framework is concerned).
*
*/
public interface IOutputConnector
{
        public static final String _rcsid = "@(#)$Id$";

        // Document statuses
    
        /** Document accepted */
        public final static int DOCUMENTSTATUS_ACCEPTED = 0;
        /** Document permanently rejected */
        public final static int DOCUMENTSTATUS_REJECTED = 1;
    
        /** Install the connector.
        * This method is called to initialize persistent storage for the connector, such as database tables etc.
        * It is called when the connector is registered.
        *@param threadContext is the current thread context.
        */
        public void install(IThreadContext threadContext)
                throws MetacartaException;

        /** Uninstall the connector.
        * This method is called to remove persistent storage for the connector, such as database tables etc.
        * It is called when the connector is deregistered.
        *@param threadContext is the current thread context.
        */
        public void deinstall(IThreadContext threadContext)
                throws MetacartaException;

        /** Return the path for the UI interface JSP elements.
        * These JSP's must be provided to allow the connector to be configured, and to
        * permit it to present connector-specific metadata specification information in the UI.
        * This method should return the name of the folder, under the <webapp>/output/
        * area, where the appropriate JSP's can be found.  The name should NOT have a slash in it.
        *@return the folder part
        */
        public String getJSPFolder();

        /** Return a list of activities that this connector generates.
        *@return the set of activities.
        */
        public String[] getActivitiesList();
        
        /** Connect.  The configuration parameters are included.
        *@param configParams are the configuration parameters for this connection.
        * Note well: There are no exceptions allowed from this call, since it is expected to mainly establish connection parameters.
        */
        public void connect(ConfigParams configParams);

        // All methods below this line will ONLY be called if a connect() call succeeded
        // on this instance!

        /** Test the connection.  Returns a string describing the connection integrity.
        *@return the connection's status as a displayable string.
        */
        public String check()
                throws MetacartaException;

        /** This method is periodically called for all connectors that are connected but not
        * in active use.
        */
        public void poll()
                throws MetacartaException;

        /** Close the connection.  Call this before discarding the repository connector.
        */
        public void disconnect()
                throws MetacartaException;

        /** Clear out any state information specific to a given thread.
        * This method is called when this object is returned to the connection pool.
        */
        public void clearThreadContext();

        /** Attach to a new thread.
        *@param threadContext is the new thread context.
        */
        public void setThreadContext(IThreadContext threadContext);

        /** Get configuration information.
        *@return the configuration information for this connector.
        */
        public ConfigParams getConfiguration();

        /** Get an output version string, given an output specification.  The output version string is used to uniquely describe the pertinent details of
        * the output specification and the configuration, to allow the Connector Framework to determine whether a document will need to be output again.
        * Note that the contents of the document cannot be considered by this method, and that a different version string (defined in IRepositoryConnector)
        * is used to describe the version of the actual document.
        *
        * This method presumes that the connector object has been configured, and it is thus able to communicate with the output data store should that be
        * necessary.
        *@param spec is the current output specification for the job that is doing the crawling.
        *@return a string, of unlimited length, which uniquely describes output configuration and specification in such a way that if two such strings are equal,
        * the document will not need to be sent again to the output data store.
        */
        public String getOutputDescription(OutputSpecification spec)
                throws MetacartaException;
                
        /** Add (or replace) a document in the output data store using the connector.
        * This method presumes that the connector object has been configured, and it is thus able to communicate with the output data store should that be
        * necessary.
        * The OutputSpecification is *not* provided to this method, because the goal is consistency, and if output is done it must be consistent with the
        * output description, since that was what was partly used to determine if output should be taking place.  So it may be necessary for this method to decode
        * an output description string in order to determine what should be done.
        *@param documentURI is the URI of the document.  The URI is presumed to be the unique identifier which the output data store will use to process
        * and serve the document.  This URI is constructed by the repository connector which fetches the document, and is thus universal across all output connectors.
        *@param outputDescription is the description string that was constructed for this document by the getOutputDescription() method.
        *@param document is the document data to be processed (handed to the output data store).
        *@param authorityNameString is the name of the authority responsible for authorizing any access tokens passed in with the repository document.  May be null.
        *@activities is the handle to an object that the implementer of an output connector may use to perform operations, such as logging processing activity.
        *@return the document status (accepted or permanently rejected).
        */
        public int addOrReplaceDocument(String documentURI, String outputDescription, RepositoryDocument document, String authorityNameString, IOutputAddActivity activities)
                throws MetacartaException, ServiceInterruption;
                
        /** Remove a document using the connector.
        * Note that the last outputDescription is included, since it may be necessary for the connector to use such information to know how to properly remove the document.
        *@param documentURI is the URI of the document.  The URI is presumed to be the unique identifier which the output data store will use to process
        * and serve the document.  This URI is constructed by the repository connector which fetches the document, and is thus universal across all output connectors.
        *@param outputDescription is the last description string that was constructed for this document by the getOutputDescription() method above.
        *@activities is the handle to an object that the implementer of an output connector may use to perform operations, such as logging processing activity.
        */
        public void removeDocument(String documentURI, String outputDescription, IOutputRemoveActivity activities)
                throws MetacartaException, ServiceInterruption;
                
}


