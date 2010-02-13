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
package com.metacarta.authorities.authority;

import com.metacarta.core.interfaces.*;
import com.metacarta.authorities.interfaces.*;
import java.util.*;
import com.metacarta.authorities.interfaces.CacheKeyFactory;
import com.metacarta.authorities.system.Metacarta;

import com.metacarta.crawler.interfaces.IRepositoryConnectionManager;
import com.metacarta.crawler.interfaces.RepositoryConnectionManagerFactory;

/** Implementation of the authority connection manager functionality.
*/
public class AuthorityConnectionManager extends com.metacarta.core.database.BaseTable implements IAuthorityConnectionManager
{
	public static final String _rcsid = "@(#)$Id$";

	// Special field suffix
	private final static String passwordSuffix = "password";

	protected final static String nameField = "authorityname";	// Changed this to work around a bug in postgresql
	protected final static String descriptionField = "description";
	protected final static String classNameField = "classname";
	protected final static String maxCountField = "maxcount";
	protected final static String configField = "configxml";

	// Cache manager
	ICacheManager cacheManager;
	// Thread context
	IThreadContext threadContext;

	/** Constructor.
	*@param threadContext is the thread context.
	*/
	public AuthorityConnectionManager(IThreadContext threadContext, IDBInterface database)
		throws MetacartaException
	{
		super(database,"authconnections");

		cacheManager = CacheManagerFactory.make(threadContext);
		this.threadContext = threadContext;
	}

	/** Install the manager.
	*/
	public void install()
		throws MetacartaException
	{
		beginTransaction();
		try
		{
			Map existing = getTableSchema(null,null);
			if (existing == null)
			{
				// Install the "objects" table.
				HashMap map = new HashMap();
				map.put(nameField,new ColumnDescription("VARCHAR(32)",true,false,null,null,false));
				map.put(descriptionField,new ColumnDescription("VARCHAR(255)",false,true,null,null,false));
				map.put(classNameField,new ColumnDescription("VARCHAR(255)",false,false,null,null,false));
				map.put(maxCountField,new ColumnDescription("BIGINT",false,false,null,null,false));
				map.put(configField,new ColumnDescription("LONGTEXT",false,true,null,null,false));
				performCreate(map,null);
			}
			else
			{
				if (existing.get(configField) == null)
				{
					// Add the configField column, and transfer data into it from the old configuration table
					HashMap map = new HashMap();
					map.put(configField,new ColumnDescription("LONGTEXT",false,true,null,null,false));
					performAlter(map,null,null,null);
					IResultSet set = getDBInterface().performQuery("SELECT * FROM authconfigs",null,null,null);
					int i = 0;
					Map xmlMap = new HashMap();
					while (i < set.getRowCount())
					{
						IResultRow row = set.getRow(i++);
						String owner = (String)row.getValue("owner");
						String name = (String)row.getValue("name");
						String value = (String)row.getValue("value");
						ConfigParams cp = (ConfigParams)xmlMap.get(owner);
						if (cp == null)
						{
							cp = new ConfigParams();
							xmlMap.put(owner,cp);
						}
						cp.setParameter(name,value);
					}
					getDBInterface().performDrop("authconfigs",null);
					Iterator iter = xmlMap.keySet().iterator();
					while (iter.hasNext())
					{
						String owner = (String)iter.next();
						ConfigParams cp = (ConfigParams)xmlMap.get(owner);
						map = new HashMap();
						ArrayList list = new ArrayList();
						list.add(owner);
						map.put(configField,cp.toXML());
						performUpdate(map,"WHERE "+nameField+"=?",list,null);
					}
				}
			}
		}
		catch (MetacartaException e)
		{
			signalRollback();
			throw e;
		}
		catch (Error e)
		{
			signalRollback();
			throw e;
		}
		finally
		{
			endTransaction();
		}
	}

	/** Uninstall the manager.
	*/
	public void deinstall()
		throws MetacartaException
	{
		performDrop(null);
	}

	/** Export configuration */
	public void exportConfiguration(java.io.OutputStream os)
		throws java.io.IOException, MetacartaException
	{
		// Write a version indicator
		Metacarta.writeDword(os,1);
		// Get the authority list
		IAuthorityConnection[] list = getAllConnections();
		// Write the number of authorities
		Metacarta.writeDword(os,list.length);
		// Loop through the list and write the individual authority info
		int i = 0;
		while (i < list.length)
		{
			IAuthorityConnection conn = list[i++];
			Metacarta.writeString(os,conn.getName());
			Metacarta.writeString(os,conn.getDescription());
			Metacarta.writeString(os,conn.getClassName());
			Metacarta.writeString(os,conn.getConfigParams().toXML());
			Metacarta.writeDword(os,conn.getMaxConnections());
		}
	}
	
	/** Import configuration */
	public void importConfiguration(java.io.InputStream is)
		throws java.io.IOException, MetacartaException
	{
		int version = Metacarta.readDword(is);
		if (version != 1)
			throw new java.io.IOException("Unknown authority configuration version: "+Integer.toString(version));
		int count = Metacarta.readDword(is);
		int i = 0;
		while (i < count)
		{
			IAuthorityConnection conn = create();
			conn.setName(Metacarta.readString(is));
			conn.setDescription(Metacarta.readString(is));
			conn.setClassName(Metacarta.readString(is));
			conn.getConfigParams().fromXML(Metacarta.readString(is));
			conn.setMaxConnections(Metacarta.readDword(is));
			// Attempt to save this connection
			save(conn);
			i++;
		}
	}

	/** Obtain a list of the repository connections, ordered by name.
	*@return an array of connection objects.
	*/
	public IAuthorityConnection[] getAllConnections()
		throws MetacartaException
	{
		beginTransaction();
		try
		{
			// Read all the tools
			StringSetBuffer ssb = new StringSetBuffer();
			ssb.add(getAuthorityConnectionsKey());
			StringSet localCacheKeys = new StringSet(ssb);
			IResultSet set = performQuery("SELECT "+nameField+",lower("+nameField+") AS sortfield FROM "+getTableName()+" ORDER BY sortfield ASC",null,
				localCacheKeys,null);
			String[] names = new String[set.getRowCount()];
			int i = 0;
			while (i < names.length)
			{
				IResultRow row = set.getRow(i);
				names[i] = row.getValue(nameField).toString();
				i++;
			}
			return loadMultiple(names);
		}
		catch (MetacartaException e)
		{
			signalRollback();
			throw e;
		}
		catch (Error e)
		{
			signalRollback();
			throw e;
		}
		finally
		{
			endTransaction();
		}
	}

	/** Load a repository connection by name.
	*@param name is the name of the repository connection.
	*@return the loaded connection object, or null if not found.
	*/
	public IAuthorityConnection load(String name)
		throws MetacartaException
	{
		return loadMultiple(new String[]{name})[0];
	}

	/** Load multiple repository connections by name.
	*@param names are the names to load.
	*@return the loaded connection objects.
	*/
	public IAuthorityConnection[] loadMultiple(String[] names)
		throws MetacartaException
	{
		// Build description objects
		AuthorityConnectionDescription[] objectDescriptions = new AuthorityConnectionDescription[names.length];
		int i = 0;
		StringSetBuffer ssb = new StringSetBuffer();
		while (i < names.length)
		{
			ssb.clear();
			ssb.add(getAuthorityConnectionKey(names[i]));
			objectDescriptions[i] = new AuthorityConnectionDescription(names[i],new StringSet(ssb));
			i++;
		}

		AuthorityConnectionExecutor exec = new AuthorityConnectionExecutor(this,objectDescriptions);
		cacheManager.findObjectsAndExecute(objectDescriptions,null,exec,getTransactionID());
		return exec.getResults();
	}

	/** Create a new repository connection object.
	*@return the new object.
	*/
	public IAuthorityConnection create()
		throws MetacartaException
	{
		AuthorityConnection rval = new AuthorityConnection();
		return rval;
	}

	/** Save a repository connection object.
	*@param object is the object to save.
	*/
	public void save(IAuthorityConnection object)
		throws MetacartaException
	{
		StringSetBuffer ssb = new StringSetBuffer();
		ssb.add(getAuthorityConnectionsKey());
		ssb.add(getAuthorityConnectionKey(object.getName()));
		StringSet cacheKeys = new StringSet(ssb);
		ICacheHandle ch = cacheManager.enterCache(null,cacheKeys,getTransactionID());
		try
		{
		    beginTransaction();
		    try
		    {
			performLock();
			Metacarta.noteConfigurationChange();
			// See whether the instance exists
			ArrayList params = new ArrayList();
			params.add(object.getName());
			IResultSet set = performQuery("SELECT * FROM "+getTableName()+" WHERE "+
				nameField+"=? FOR UPDATE",params,null,null);
			HashMap values = new HashMap();
			values.put(descriptionField,object.getDescription());
			values.put(classNameField,object.getClassName());
			values.put(maxCountField,new Long((long)object.getMaxConnections()));
			values.put(configField,object.getConfigParams().toXML());

			if (set.getRowCount() > 0)
			{
				// Update
				params.clear();
				params.add(object.getName());
				performUpdate(values," WHERE "+nameField+"=?",params,null);
			}
			else
			{
				// Insert
				values.put(nameField,object.getName());
				// We only need the general key because this is new.
				performInsert(values,null);
			}

			cacheManager.invalidateKeys(ch);
		    }
		    catch (MetacartaException e)
		    {
			signalRollback();
			throw e;
		    }
		    catch (Error e)
		    {
			signalRollback();
			throw e;
		    }
		    finally
		    {
			endTransaction();
		    }
		}
		finally
		{
			cacheManager.leaveCache(ch);
		}
	}

	/** Delete a repository connection.
	*@param name is the name of the connection to delete.  If the
	* name does not exist, no error is returned.
	*/
	public void delete(String name)
		throws MetacartaException
	{
		// Grab repository connection manager handle, to check on legality of deletion.
		IRepositoryConnectionManager repoManager = RepositoryConnectionManagerFactory.make(threadContext);

		StringSetBuffer ssb = new StringSetBuffer();
		ssb.add(getAuthorityConnectionsKey());
		ssb.add(getAuthorityConnectionKey(name));
		StringSet cacheKeys = new StringSet(ssb);
		ICacheHandle ch = cacheManager.enterCache(null,cacheKeys,getTransactionID());
		try
		{
			beginTransaction();
			try
			{
				// Check if anything refers to this connection name
				if (repoManager.isReferenced(name))
				 	throw new MetacartaException("Can't delete authority connection '"+name+"': existing repository connections refer to it");
				Metacarta.noteConfigurationChange();
				ArrayList params = new ArrayList();
				params.add(name);
				performDelete("WHERE "+nameField+"=?",params,null);
				cacheManager.invalidateKeys(ch);
			}
			catch (MetacartaException e)
			{
				signalRollback();
				throw e;
			}
			catch (Error e)
			{
				signalRollback();
				throw e;
			}
			finally
			{
				endTransaction();
			}
		}
		finally
		{
			cacheManager.leaveCache(ch);
		}

	}

	/** Get the authority connection name column.
	*@return the name column.
	*/
	public String getAuthorityNameColumn()
	{
		return nameField;
	}

	// Caching strategy: Individual connection descriptions are cached, and there is a global cache key for the list of
	// repository connections.

	/** Construct a key which represents the general list of repository connectors.
	*@return the cache key.
	*/
	protected static String getAuthorityConnectionsKey()
	{
		return CacheKeyFactory.makeAuthorityConnectionsKey();
	}

	/** Construct a key which represents an individual repository connection.
	*@param connectionName is the name of the connector.
	*@return the cache key.
	*/
	protected static String getAuthorityConnectionKey(String connectionName)
	{
		return CacheKeyFactory.makeAuthorityConnectionKey(connectionName);
	}

	// Other utility methods.

	/** Fetch multiple repository connections at a single time.
	*@param connectionNames are a list of connection names.
	*@return the corresponding repository connection objects.
	*/
	protected AuthorityConnection[] getAuthorityConnectionsMultiple(String[] connectionNames)
		throws MetacartaException
	{
		AuthorityConnection[] rval = new AuthorityConnection[connectionNames.length];
		HashMap returnIndex = new HashMap();
		int i = 0;
		while (i < connectionNames.length)
		{
			rval[i] = null;
			returnIndex.put(connectionNames[i],new Integer(i));
			i++;
		}
		beginTransaction();
		try
		{
			i = 0;
			StringBuffer sb = new StringBuffer();
			ArrayList params = new ArrayList();
			int j = 0;
			int maxIn = getMaxInClause();
			while (i < connectionNames.length)
			{
				if (j == maxIn)
				{
					getAuthorityConnectionsChunk(rval,returnIndex,sb.toString(),params);
					sb.setLength(0);
					params.clear();
					j = 0;
				}
				if (j > 0)
					sb.append(',');
				sb.append('?');
				params.add(connectionNames[i]);
				i++;
				j++;
			}
			if (j > 0)
				getAuthorityConnectionsChunk(rval,returnIndex,sb.toString(),params);
			return rval;
		}
		catch (Error e)
		{
			signalRollback();
			throw e;
		}
		catch (MetacartaException e)
		{
			signalRollback();
			throw e;
		}
		finally
		{
			endTransaction();
		}
	}

	/** Read a chunk of repository connections.
	*@param rval is the place to put the read policies.
	*@param returnIndex is a map from the object id (resource id) and the rval index.
	*@param idList is the list of id's.
	*@param params is the set of parameters.
	*/
	protected void getAuthorityConnectionsChunk(AuthorityConnection[] rval, Map returnIndex, String idList, ArrayList params)
		throws MetacartaException
	{
		IResultSet set;
		set = performQuery("SELECT * FROM "+getTableName()+" WHERE "+
			nameField+" IN ("+idList+")",params,null,null);
		int i = 0;
		while (i < set.getRowCount())
		{
			IResultRow row = set.getRow(i++);
			String name = row.getValue(nameField).toString();
			int index = ((Integer)returnIndex.get(name)).intValue();
			AuthorityConnection rc = new AuthorityConnection();
			rc.setName(name);
			rc.setDescription((String)row.getValue(descriptionField));
			rc.setClassName((String)row.getValue(classNameField));
			rc.setMaxConnections((int)((Long)row.getValue(maxCountField)).longValue());
			String xml = (String)row.getValue(configField);
			if (xml != null && xml.length() > 0)
				rc.getConfigParams().fromXML(xml);
			rval[index] = rc;
		}
	}

	// The cached instance will be a AuthorityConnection.  The cached version will be duplicated when it is returned
	// from the cache.
	//
	// The description object is based completely on the name.

	/** This is the object description for a repository connection object.
	*/
	protected static class AuthorityConnectionDescription extends com.metacarta.core.cachemanager.BaseDescription
	{
		protected String connectionName;
		protected String criticalSectionName;
		protected StringSet cacheKeys;

		public AuthorityConnectionDescription(String connectionName, StringSet invKeys)
		{
			super("authorityconnectioncache");
			this.connectionName = connectionName;
			criticalSectionName = getClass().getName()+"-"+connectionName;
			cacheKeys = invKeys;
		}

		public String getConnectionName()
		{
			return connectionName;
		}

		public int hashCode()
		{
			return connectionName.hashCode();
		}

		public boolean equals(Object o)
		{
			if (!(o instanceof AuthorityConnectionDescription))
				return false;
			AuthorityConnectionDescription d = (AuthorityConnectionDescription)o;
			return d.connectionName.equals(connectionName);
		}

		public String getCriticalSectionName()
		{
			return criticalSectionName;
		}

		/** Get the cache keys for an object (which may or may not exist yet in
		* the cache).  This method is called in order for cache manager to throw the correct locks.
		* @return the object's cache keys, or null if the object should not
		* be cached.
		*/
		public StringSet getObjectKeys()
		{
			return cacheKeys;
		}

	}

	/** This is the executor object for locating repository connection objects.
	*/
	protected static class AuthorityConnectionExecutor extends com.metacarta.core.cachemanager.ExecutorBase
	{
		// Member variables
		protected AuthorityConnectionManager thisManager;
		protected AuthorityConnection[] returnValues;
		protected HashMap returnMap = new HashMap();

		/** Constructor.
		*@param manager is the ToolManager.
		*@param objectDescriptions are the object descriptions.
		*/
		public AuthorityConnectionExecutor(AuthorityConnectionManager manager, AuthorityConnectionDescription[] objectDescriptions)
		{
			super();
			thisManager = manager;
			returnValues = new AuthorityConnection[objectDescriptions.length];
			int i = 0;
			while (i < objectDescriptions.length)
			{
				returnMap.put(objectDescriptions[i].getConnectionName(),new Integer(i));
				i++;
			}
		}

		/** Get the result.
		*@return the looked-up or read cached instances.
		*/
		public AuthorityConnection[] getResults()
		{
			return returnValues;
		}

		/** Create a set of new objects to operate on and cache.  This method is called only
		* if the specified object(s) are NOT available in the cache.  The specified objects
		* should be created and returned; if they are not created, it means that the
		* execution cannot proceed, and the execute() method will not be called.
		* @param objectDescriptions is the set of unique identifier of the object.
		* @return the newly created objects to cache, or null, if any object cannot be created.
		*  The order of the returned objects must correspond to the order of the object descriptinos.
		*/
		public Object[] create(ICacheDescription[] objectDescriptions) throws MetacartaException
		{
			// Turn the object descriptions into the parameters for the ToolInstance requests
			String[] connectionNames = new String[objectDescriptions.length];
			int i = 0;
			while (i < connectionNames.length)
			{
				AuthorityConnectionDescription desc = (AuthorityConnectionDescription)objectDescriptions[i];
				connectionNames[i] = desc.getConnectionName();
				i++;
			}

			return thisManager.getAuthorityConnectionsMultiple(connectionNames);
		}


		/** Notify the implementing class of the existence of a cached version of the
		* object.  The object is passed to this method so that the execute() method below
		* will have it available to operate on.  This method is also called for all objects
		* that are freshly created as well.
		* @param objectDescription is the unique identifier of the object.
		* @param cachedObject is the cached object.
		*/
		public void exists(ICacheDescription objectDescription, Object cachedObject) throws MetacartaException
		{
			// Cast what came in as what it really is
			AuthorityConnectionDescription objectDesc = (AuthorityConnectionDescription)objectDescription;
			AuthorityConnection ci = (AuthorityConnection)cachedObject;

			// Duplicate it!
			if (ci != null)
				ci = ci.duplicate();

			// In order to make the indexes line up, we need to use the hashtable built by
			// the constructor.
			returnValues[((Integer)returnMap.get(objectDesc.getConnectionName())).intValue()] = ci;
		}

		/** Perform the desired operation.  This method is called after either createGetObject()
		* or exists() is called for every requested object.
		*/
		public void execute() throws MetacartaException
		{
			// Does nothing; we only want to fetch objects in this cacher.
		}


	}

}
