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
package com.metacarta.crawler.connectors;

import com.metacarta.core.interfaces.*;
import com.metacarta.agents.interfaces.*;
import com.metacarta.crawler.interfaces.*;

import java.io.*;
import java.util.*;

/** This base class describes an instance of a connection between a repository and Metacarta's
* standard "pull" ingestion agent.
*
* Each instance of this interface is used in only one thread at a time.  Connection Pooling
* on these kinds of objects is performed by the factory which instantiates repository connectors
* from symbolic names and config parameters, and is pooled by these parameters.  That is, a pooled connector
* handle is used only if all the connection parameters for the handle match.
*
* Implementers of this interface should provide a default constructor which has this signature:
*
* xxx();
*
* Connectors are either configured or not.  If configured, they will persist in a pool, and be
* reused multiple times.  Certain methods of a connector may be called before the connector is
* configured.  This includes basically all methods that permit inspection of the connector's
* capabilities.  The complete list is:
*
*
* The purpose of the repository connector is to allow documents to be fetched from the repository.
*
* Each repository connector describes a set of documents that are known only to that connector.
* It therefore establishes a space of document identifiers.  Each connector will only ever be
* asked to deal with identifiers that have in some way originated from the connector.
*
* Documents are fetched in three stages.  First, the getDocuments() method is called in the connector
* implementation.  This returns a set of document identifiers.  The document identifiers are used to
* obtain the current document version strings in the second stage, using the getDocumentVersions() method.
* The last stage is processDocuments(), which queues up any additional documents needed, and also ingests.
* This method will not be called if the document version seems to indicate that no document change took
* place.
*/
public abstract class BaseRepositoryConnector implements IRepositoryConnector
{
	public static final String _rcsid = "@(#)$Id$";

	// Config params
	protected ConfigParams params = null;

	// Current thread context
	protected IThreadContext currentContext = null;

	/** Tell the world what model this connector uses for getDocumentIdentifiers().
	* This must return a model value as specified above.
	*@return the model type value.
	*/
	public int getConnectorModel()
	{
		// Return the simplest model - full everything
		return MODEL_ALL;
	}

	/** Install the connector.
	* This method is called to initialize persistent storage for the connector, such as database tables etc.
	* It is called when the connector is registered.
	*@param threadContext is the current thread context.
	*/
	public void install(IThreadContext threadContext)
		throws MetacartaException
	{
		// Base install does nothing
	}

	/** Uninstall the connector.
	* This method is called to remove persistent storage for the connector, such as database tables etc.
	* It is called when the connector is deregistered.
	*@param threadContext is the current thread context.
	*/
	public void deinstall(IThreadContext threadContext)
		throws MetacartaException
	{
		// Base uninstall does nothing
	}

	/** Return the list of activities that this connector supports (i.e. writes into the log).
	*@return the list.
	*/
	public String[] getActivitiesList()
	{
		return new String[0];
	}

	/** Return the list of relationship types that this connector recognizes.
	*@return the list.
	*/
	public String[] getRelationshipTypes()
	{
		// The base situation is that there are no relationships.
		return new String[0];
	}

	/** Connect.  The configuration parameters are included.
	*@param configParams are the configuration parameters for this connection.
	*/
	public void connect(ConfigParams configParams)
	{
		params = configParams;
	}

	// All methods below this line will ONLY be called if a connect() call succeeded
	// on this instance!

	/** Test the connection.  Returns a string describing the connection integrity.
	*@return the connection's status as a displayable string.
	*/
	public String check()
		throws MetacartaException
	{
		// Base version returns "OK" status.
		return "Connection working";
	}

	/** This method is periodically called for all connectors that are connected but not
	* in active use.
	*/
	public void poll()
		throws MetacartaException
	{
		// Base version does nothing
	}

	/** Close the connection.  Call this before discarding the repository connector.
	*/
	public void disconnect()
		throws MetacartaException
	{
		params = null;
	}

	/** Clear out any state information specific to a given thread.
	* This method is called when this object is returned to the connection pool.
	*/
	public void clearThreadContext()
	{
		currentContext = null;
	}

	/** Attach to a new thread.
	*@param threadContext is the new thread context.
	*/
	public void setThreadContext(IThreadContext threadContext)
	{
		currentContext = threadContext;
	}


	/** Get the bin name strings for a document identifier.  The bin name describes the queue to which the
	* document will be assigned for throttling purposes.  Throttling controls the rate at which items in a
	* given queue are fetched; it does not say anything about the overall fetch rate, which may operate on
	* multiple queues or bins.
	* For example, if you implement a web crawler, a good choice of bin name would be the server name, since
	* that is likely to correspond to a real resource that will need real throttle protection.
	*@param documentIdentifier is the document identifier.
	*@return the set of bin names.  If an empty array is returned, it is equivalent to there being no request
	* rate throttling available for this identifier.
	*/
	public String[] getBinNames(String documentIdentifier)
	{
		// Base version has one bin for all documents.  Use empty string for this since "*" would make
		// regexps be difficult to write.
		return new String[]{""};
	}

	/** Get configuration information.
	*@return the configuration information for this class.
	*/
	public ConfigParams getConfiguration()
	{
		return params;
	}

	/** Queue "seed" documents.  Seed documents are the starting places for crawling activity.  Documents
	* are seeded when this method calls appropriate methods in the passed in ISeedingActivity object.
	*
	* This method can choose to find repository changes that happen only during the specified time interval.
	* The seeds recorded by this method will be viewed by the framework based on what the
	* getConnectorModel() method returns. 
	*
	* It is not a big problem if the connector chooses to create more seeds than are
	* strictly necessary; it is merely a question of overall work required.
	*
	* The times passed to this method may be interpreted for greatest efficiency.  The time ranges
	* any given job uses with this connector will not overlap, but will proceed starting at 0 and going
	* to the "current time", each time the job is run.  For continuous crawling jobs, this method will
	* be called once, when the job starts, and at various periodic intervals as the job executes.
	* 
	* When a job's specification is changed, the framework automatically resets the seeding start time to 0.  The
	* seeding start time may also be set to 0 on each job run, depending on the connector model returned by
	* getConnectorModel().
	*
	* Note that it is always ok to send MORE documents rather than less to this method.
	*@param activities is the interface this method should use to perform whatever framework actions are desired.
	*@param spec is a document specification (that comes from the job).
	*@param startTime is the beginning of the time range to consider, inclusive.
	*@param endTime is the end of the time range to consider, exclusive.
	*@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
	*/
	public void addSeedDocuments(ISeedingActivity activities, DocumentSpecification spec,
		long startTime, long endTime, int jobMode)
		throws MetacartaException, ServiceInterruption
	{
		addSeedDocuments(activities,spec,startTime,endTime);
	}

	/** Queue "seed" documents.  Seed documents are the starting places for crawling activity.  Documents
	* are seeded when this method calls appropriate methods in the passed in ISeedingActivity object.
	*
	* This method can choose to find repository changes that happen only during the specified time interval.
	* The seeds recorded by this method will be viewed by the framework based on what the
	* getConnectorModel() method returns. 
	*
	* It is not a big problem if the connector chooses to create more seeds than are
	* strictly necessary; it is merely a question of overall work required.
	*
	* The times passed to this method may be interpreted for greatest efficiency.  The time ranges
	* any given job uses with this connector will not overlap, but will proceed starting at 0 and going
	* to the "current time", each time the job is run.  For continuous crawling jobs, this method will
	* be called once, when the job starts, and at various periodic intervals as the job executes.
	* 
	* When a job's specification is changed, the framework automatically resets the seeding start time to 0.  The
	* seeding start time may also be set to 0 on each job run, depending on the connector model returned by
	* getConnectorModel().
	*
	* Note that it is always ok to send MORE documents rather than less to this method.
	*@param activities is the interface this method should use to perform whatever framework actions are desired.
	*@param spec is a document specification (that comes from the job).
	*@param startTime is the beginning of the time range to consider, inclusive.
	*@param endTime is the end of the time range to consider, exclusive.
	*/
	public void addSeedDocuments(ISeedingActivity activities, DocumentSpecification spec,
		long startTime, long endTime)
		throws MetacartaException, ServiceInterruption
	{
		// Call the old-style methods that get document identifiers, and then queue
		// them using the new activities-based methods
		IDocumentIdentifierStream ids = getDocumentIdentifiers(activities,spec,startTime,endTime);
		if (ids != null)
		{
			try
			{
				while (true)
				{
					String id = ids.getNextIdentifier();
					if (id == null) break;
					activities.addSeedDocument(id);
				}
			}
			finally
			{
				ids.close();
			}
		}
		ids = getRemainingDocumentIdentifiers(activities,spec,startTime,endTime);
		if (ids != null)
		{
			try
			{
				while (true)
				{
					String id = ids.getNextIdentifier();
					if (id == null) break;
					activities.addUnqueuedSeedDocument(id);
				}
			}
			finally
			{
				ids.close();
			}
		}
	}

	/** The long version of getDocumentIdentifiers.
	*@param activities is the interface this method should use to perform whatever framework actions are desired.
	*@param spec is a document specification (that comes from the job).
	*@param startTime is the beginning of the time range to consider, inclusive.
	*@param endTime is the end of the time range to consider, exclusive.
	*@return the local document identifiers that should be added to the queue, as a stream.
	*/
	public IDocumentIdentifierStream getDocumentIdentifiers(ISeedingActivity activities, DocumentSpecification spec,
		long startTime, long endTime)
		throws MetacartaException, ServiceInterruption
	{
		return getDocumentIdentifiers(spec,startTime,endTime);
	}

	/** The short version of getDocumentIdentifiers.
	*@param spec is a document specification (that comes from the job).
	*@param startTime is the beginning of the time range to consider, inclusive.
	*@param endTime is the end of the time range to consider, exclusive.
	*@return the local document identifiers that should be added to the queue, as a stream.
	*/
	public IDocumentIdentifierStream getDocumentIdentifiers(DocumentSpecification spec,
		long startTime, long endTime)
		throws MetacartaException, ServiceInterruption
	{
		// Something provided here so we can override either one.
		return null;
	}

	/** This method returns the document identifiers that should be considered part of the seeds, but do not need to be
	* queued for processing at this time.  This method is used to keep the hopcount tables up to date.  It is
	* allowed to return more identifiers than it strictly needs to, specifically identifiers that were also returned
	* by the getDocumentIdentifiers() method above.  However, it must constrain the identifiers it returns by the document
	* specification.
	* This method is only required to do anything if the connector supports hopcount determination (which it should signal by
	* having more than zero legal relationship types returned by the getRelationshipTypes() method.
	*
	*@param activities is the interface this method should use to perform whatever framework actions are desired.
	*@param spec is a document specification (that comes from the job).
	*@param startTime is the beginning of the time range that was passed to getDocumentIdentifiers().
	*@param endTime is the end of the time range to passed to getDocumentIdentifiers().
	*@return the local document identifiers that should be added to the queue, as a stream, or null, if none need to be
	* returned.
	*/
	public IDocumentIdentifierStream getRemainingDocumentIdentifiers(ISeedingActivity activities, DocumentSpecification spec,
		long startTime, long endTime)
		throws MetacartaException, ServiceInterruption
	{
		// Usually we don't need to worry about this.
		return null;
	}

	/** Get document versions given an array of document identifiers.
	* This method is called for EVERY document that is considered. It is
	* therefore important to perform as little work as possible here.
	*@param documentIdentifiers is the array of local document identifiers, as understood by this connector.
	*@param oldVersions is the corresponding array of version strings that have been saved for the document identifiers.
	*   A null value indicates that this is a first-time fetch, while an empty string indicates that the previous document
	*   had an empty version string.
	*@param activities is the interface this method should use to perform whatever framework actions are desired.
	*@param spec is the current document specification for the current job.  If there is a dependency on this
	* specification, then the version string should include the pertinent data, so that reingestion will occur
	* when the specification changes.  This is primarily useful for metadata.
	*@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
	*@param usesDefaultAuthority will be true only if the authority in use for these documents is the default one.
	*@return the corresponding version strings, with null in the places where the document no longer exists.
	* Empty version strings indicate that there is no versioning ability for the corresponding document, and the document
	* will always be processed.
	*/
	public String[] getDocumentVersions(String[] documentIdentifiers, String[] oldVersions, IVersionActivity activities,
		DocumentSpecification spec, int jobMode, boolean usesDefaultAuthority)
		throws MetacartaException, ServiceInterruption
	{
		return getDocumentVersions(documentIdentifiers,oldVersions,activities,spec,jobMode);
	}
	
	/** Get document versions given an array of document identifiers.
	* This method is called for EVERY document that is considered. It is
	* therefore important to perform as little work as possible here.
	*@param documentIdentifiers is the array of local document identifiers, as understood by this connector.
	*@param oldVersions is the corresponding array of version strings that have been saved for the document identifiers.
	*   A null value indicates that this is a first-time fetch, while an empty string indicates that the previous document
	*   had an empty version string.
	*@param activities is the interface this method should use to perform whatever framework actions are desired.
	*@param spec is the current document specification for the current job.  If there is a dependency on this
	* specification, then the version string should include the pertinent data, so that reingestion will occur
	* when the specification changes.  This is primarily useful for metadata.
	*@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
	*@return the corresponding version strings, with null in the places where the document no longer exists.
	* Empty version strings indicate that there is no versioning ability for the corresponding document, and the document
	* will always be processed.
	*/
	public String[] getDocumentVersions(String[] documentIdentifiers, String[] oldVersions, IVersionActivity activities,
		DocumentSpecification spec, int jobMode)
		throws MetacartaException, ServiceInterruption
	{
		return getDocumentVersions(documentIdentifiers,oldVersions,activities,spec);
	}

	/** Get document versions given an array of document identifiers.
	* This method is called for EVERY document that is considered. It is
	* therefore important to perform as little work as possible here.
	*@param documentIdentifiers is the array of local document identifiers, as understood by this connector.
	*@param oldVersions is the corresponding array of version strings that have been saved for the document identifiers.
	*   A null value indicates that this is a first-time fetch, while an empty string indicates that the previous document
	*   had an empty version string.
	*@param activities is the interface this method should use to perform whatever framework actions are desired.
	*@param spec is the current document specification for the current job.  If there is a dependency on this
	* specification, then the version string should include the pertinent data, so that reingestion will occur
	* when the specification changes.  This is primarily useful for metadata.
	*@return the corresponding version strings, with null in the places where the document no longer exists.
	* Empty version strings indicate that there is no versioning ability for the corresponding document, and the document
	* will always be processed.
	*/
	public String[] getDocumentVersions(String[] documentIdentifiers, String[] oldVersions, IVersionActivity activities, DocumentSpecification spec)
		throws MetacartaException, ServiceInterruption
	{
		return getDocumentVersions(documentIdentifiers,activities,spec);
	}

	/** The long version of getDocumentIdentifiers.
	* Get document versions given an array of document identifiers.
	* This method is called for EVERY document that is considered. It is
	* therefore important to perform as little work as possible here.
	*@param documentIdentifiers is the array of local document identifiers, as understood by this connector.
	*@param activities is the interface this method should use to perform whatever framework actions are desired.
	*@param spec is the current document specification for the current job.  If there is a dependency on this
	* specification, then the version string should include the pertinent data, so that reingestion will occur
	* when the specification changes.  This is primarily useful for metadata.
	*@return the corresponding version strings, with null in the places where the document no longer exists.
	* Empty version strings indicate that there is no versioning ability for the corresponding document, and the document
	* will always be processed.
	*/
	public String[] getDocumentVersions(String[] documentIdentifiers, IVersionActivity activities, DocumentSpecification spec)
		throws MetacartaException, ServiceInterruption
	{
		return getDocumentVersions(documentIdentifiers,spec);
	}

	/** The short version of getDocumentVersions.
	* Get document versions given an array of document identifiers.
	* This method is called for EVERY document that is considered. It is
	* therefore important to perform as little work as possible here.
	*@param documentIdentifiers is the array of local document identifiers, as understood by this connector.
	*@param spec is the current document specification for the current job.  If there is a dependency on this
	* specification, then the version string should include the pertinent data, so that reingestion will occur
	* when the specification changes.  This is primarily useful for metadata.
	*@return the corresponding version strings, with null in the places where the document no longer exists.
	* Empty version strings indicate that there is no versioning ability for the corresponding document, and the document
	* will always be processed.
	*/
	public String[] getDocumentVersions(String[] documentIdentifiers, DocumentSpecification spec)
		throws MetacartaException, ServiceInterruption
	{
		// Return unknown versions
		String[] rval = new String[documentIdentifiers.length];
		int i = 0;
		while (i < rval.length)
		{
			rval[i++] = "";
		}
		return rval;
	}

	/** Free a set of documents.  This method is called for all documents whose versions have been fetched using
	* the getDocumentVersions() method, including those that returned null versions.  It may be used to free resources
	* committed during the getDocumentVersions() method.  It is guaranteed to be called AFTER any calls to
	* processDocuments() for the documents in question.
	*@param documentIdentifiers is the set of document identifiers.
	*@param versions is the corresponding set of version identifiers (individual identifiers may be null).
	*/
	public void releaseDocumentVersions(String[] documentIdentifiers, String[] versions)
		throws MetacartaException
	{
		// Base implementation does nothing
	}

	/** Get the maximum number of documents to amalgamate together into one batch, for this connector.
	*@return the maximum number. 0 indicates "unlimited".
	*/
	public int getMaxDocumentRequest()
	{
		// Base implementation does one at a time.
		return 1;
	}

	/** Process a set of documents.
	* This is the method that should cause each document to be fetched, processed, and the results either added
	* to the queue of documents for the current job, and/or entered into the incremental ingestion manager.
	* The document specification allows this class to filter what is done based on the job.
	*@param documentIdentifiers is the set of document identifiers to process.
	*@param versions is the corresponding document versions to process, as returned by getDocumentVersions() above.
	*       The implementation may choose to ignore this parameter and always process the current version.
	*@param activities is the interface this method should use to queue up new document references
	* and ingest documents.
	*@param spec is the document specification.
	*@param scanOnly is an array corresponding to the document identifiers.  It is set to true to indicate when the processing
	* should only find other references, and should not actually call the ingestion methods.
	*@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
	*/
	public void processDocuments(String[] documentIdentifiers, String[] versions, IProcessActivity activities,
		DocumentSpecification spec, boolean[] scanOnly, int jobMode)
		throws MetacartaException, ServiceInterruption
	{
		processDocuments(documentIdentifiers,versions,activities,spec,scanOnly);
	}

	/** Process a set of documents.
	* This is the method that should cause each document to be fetched, processed, and the results either added
	* to the queue of documents for the current job, and/or entered into the incremental ingestion manager.
	* The document specification allows this class to filter what is done based on the job.
	*@param documentIdentifiers is the set of document identifiers to process.
	*@param versions is the corresponding document versions to process, as returned by getDocumentVersions() above.
	*       The implementation may choose to ignore this parameter and always process the current version.
	*@param activities is the interface this method should use to queue up new document references
	* and ingest documents.
	*@param spec is the document specification.
	*@param scanOnly is an array corresponding to the document identifiers.  It is set to true to indicate when the processing
	* should only find other references, and should not actually call the ingestion methods.
	*/
	public void processDocuments(String[] documentIdentifiers, String[] versions, IProcessActivity activities,
		DocumentSpecification spec, boolean[] scanOnly)
		throws MetacartaException, ServiceInterruption
	{
		// Does nothing; override to make something happen
	}

}


