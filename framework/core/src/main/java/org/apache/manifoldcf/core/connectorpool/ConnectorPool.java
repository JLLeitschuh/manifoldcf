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
package org.apache.manifoldcf.core.connectorpool;

import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.core.system.ManifoldCF;

import java.util.*;
import java.io.*;
import java.lang.reflect.*;

/** This is the base factory class for all ConnectorPool objects.
*/
public abstract class ConnectorPool<T extends IConnector>
{
  public static final String _rcsid = "@(#)$Id$";

  /** Service type prefix */
  protected final String serviceTypePrefix;

  /** Pool hash table. Keyed by connection name; value is Pool */
  protected final Map<String,Pool> poolHash = new HashMap<String,Pool>();

  protected ConnectorPool(String serviceTypePrefix)
  {
    this.serviceTypePrefix = serviceTypePrefix;
  }

  // Protected methods
  
  /** Override this method to hook into a connector manager.
  */
  protected abstract boolean isInstalled(IThreadContext tc, String className)
    throws ManifoldCFException;
  
  /** Override this method to check if a connection name is still valid.
  */
  protected abstract boolean isConnectionNameValid(IThreadContext tc, String connectionName)
    throws ManifoldCFException;
  
  /** Get a connector instance.
  *@param className is the class name.
  *@return the instance.
  */
  protected T createConnectorInstance(IThreadContext threadContext, String className)
    throws ManifoldCFException
  {
    if (!isInstalled(threadContext,className))
      return null;

    try
    {
      Class theClass = ManifoldCF.findClass(className);
      Class[] argumentClasses = new Class[0];
      // Look for a constructor
      Constructor c = theClass.getConstructor(argumentClasses);
      Object[] arguments = new Object[0];
      Object o = c.newInstance(arguments);
      try
      {
        return (T)o;
      }
      catch (ClassCastException e)
      {
        throw new ManifoldCFException("Class '"+className+"' does not implement IConnector.");
      }
    }
    catch (InvocationTargetException e)
    {
      Throwable z = e.getTargetException();
      if (z instanceof Error)
        throw (Error)z;
      else if (z instanceof RuntimeException)
        throw (RuntimeException)z;
      else if (z instanceof ManifoldCFException)
        throw (ManifoldCFException)z;
      else
        throw new RuntimeException("Unknown exception type: "+z.getClass().getName()+": "+z.getMessage(),z);
    }
    catch (ClassNotFoundException e)
    {
      // Equivalent to the connector not being installed
      return null;
      //throw new ManifoldCFException("No connector class '"+className+"' was found.",e);
    }
    catch (NoSuchMethodException e)
    {
      throw new ManifoldCFException("No appropriate constructor for IConnector implementation '"+
        className+"'.  Need xxx(ConfigParams).",
        e);
    }
    catch (SecurityException e)
    {
      throw new ManifoldCFException("Protected constructor for IConnector implementation '"+className+"'",
        e);
    }
    catch (IllegalAccessException e)
    {
      throw new ManifoldCFException("Unavailable constructor for IConnector implementation '"+className+"'",
        e);
    }
    catch (IllegalArgumentException e)
    {
      throw new ManifoldCFException("Shouldn't happen!!!",e);
    }
    catch (InstantiationException e)
    {
      throw new ManifoldCFException("InstantiationException for IConnector implementation '"+className+"'",
        e);
    }
    catch (ExceptionInInitializerError e)
    {
      throw new ManifoldCFException("ExceptionInInitializerError for IConnector implementation '"+className+"'",
        e);
    }

  }

  /** Get multiple connectors, all at once.  Do this in a particular order
  * so that any connector exhaustion will not cause a deadlock.
  */
  public T[] grabMultiple(IThreadContext threadContext, Class<T> clazz,
    String[] orderingKeys, String[] connectionNames,
    String[] classNames, ConfigParams[] configInfos, int[] maxPoolSizes)
    throws ManifoldCFException
  {
    T[] rval = (T[])Array.newInstance(clazz,classNames.length);
    Map<String,Integer> orderMap = new HashMap<String,Integer>();
    for (int i = 0; i < orderingKeys.length; i++)
    {
      if (orderMap.get(orderingKeys[i]) != null)
        throw new ManifoldCFException("Found duplicate order key");
      orderMap.put(orderingKeys[i],new Integer(i));
    }
    java.util.Arrays.sort(orderingKeys);
    for (int i = 0; i < orderingKeys.length; i++)
    {
      String orderingKey = orderingKeys[i];
      int index = orderMap.get(orderingKey).intValue();
      String connectionName = connectionNames[index];
      String className = classNames[index];
      ConfigParams cp = configInfos[index];
      int maxPoolSize = maxPoolSizes[index];
      try
      {
        T connector = grab(threadContext,connectionName,className,cp,maxPoolSize);
        rval[index] = connector;
      }
      catch (Throwable e)
      {
        while (i > 0)
        {
          i--;
          orderingKey = orderingKeys[i];
          index = orderMap.get(orderingKey).intValue();
          try
          {
            release(connectionName,rval[index]);
          }
          catch (ManifoldCFException e2)
          {
          }
        }
        if (e instanceof ManifoldCFException)
          throw (ManifoldCFException)e;
        else if (e instanceof RuntimeException)
          throw (RuntimeException)e;
        else if (e instanceof Error)
          throw (Error)e;
        else
          throw new RuntimeException("Unexpected exception type: "+e.getClass().getName()+": "+e.getMessage(),e);
      }
    }
    return rval;
  }

  /** Get a connector.
  * The connector is specified by its connection name, class, and parameters.  If the
  * class and parameters corresponding to a connection name change, then this code
  * will destroy any old connector instance that does not correspond, and create a new
  * one using the new class and parameters.
  *@param threadContext is the current thread context.
  *@param connectionName is the name of the connection.  This functions as a pool key.
  *@param className is the name of the class to get a connector for.
  *@param configInfo are the name/value pairs constituting configuration info
  * for this class.
  */
  public T grab(IThreadContext threadContext, String connectionName,
    String className, ConfigParams configInfo, int maxPoolSize)
    throws ManifoldCFException
  {
    // We want to get handles off the pool and use them.  But the
    // handles we fetch have to have the right config information.

    Pool p;
    synchronized (poolHash)
    {
      p = poolHash.get(connectionName);
      if (p == null)
      {
        p = new Pool(threadContext, maxPoolSize, connectionName);
        poolHash.put(connectionName,p);
      }
    }

    T rval = p.getConnector(threadContext,className,configInfo);

    return rval;

  }

  /** Release multiple output connectors.
  */
  public void releaseMultiple(String[] connectionNames, T[] connectors)
    throws ManifoldCFException
  {
    ManifoldCFException currentException = null;
    for (int i = 0; i < connectors.length; i++)
    {
      String connectionName = connectionNames[i];
      T c = connectors[i];
      try
      {
        release(connectionName,c);
      }
      catch (ManifoldCFException e)
      {
        if (currentException == null)
          currentException = e;
      }
    }
    if (currentException != null)
      throw currentException;
  }

  /** Release an output connector.
  *@param connectionName is the connection name.
  *@param connector is the connector to release.
  */
  public void release(String connectionName, T connector)
    throws ManifoldCFException
  {
    // If the connector is null, skip the release, because we never really got the connector in the first place.
    if (connector == null)
      return;

    // Figure out which pool this goes on, and put it there
    Pool p;
    synchronized (poolHash)
    {
      p = poolHash.get(connectionName);
    }

    p.releaseConnector(connector);
  }

  /** Idle notification for inactive output connector handles.
  * This method polls all inactive handles.
  */
  public void pollAllConnectors(IThreadContext threadContext)
    throws ManifoldCFException
  {
    // System.out.println("Pool stats:");

    // Go through the whole pool and notify everyone
    synchronized (poolHash)
    {
      Iterator<String> iter = poolHash.keySet().iterator();
      while (iter.hasNext())
      {
        String connectionName = iter.next();
        Pool p = poolHash.get(connectionName);
        if (isConnectionNameValid(threadContext,connectionName))
          p.pollAll(threadContext);
        else
        {
          p.releaseAll(threadContext);
          iter.remove();
        }
      }
    }

  }

  /** Flush only those connector handles that are currently unused.
  */
  public void flushUnusedConnectors(IThreadContext threadContext)
    throws ManifoldCFException
  {
    // Go through the whole pool and clean it out
    synchronized (poolHash)
    {
      Iterator<Pool> iter = poolHash.values().iterator();
      while (iter.hasNext())
      {
        Pool p = iter.next();
        p.flushUnused(threadContext);
      }
    }
  }

  /** Clean up all open output connector handles.
  * This method is called when the connector pool needs to be flushed,
  * to free resources.
  *@param threadContext is the local thread context.
  */
  public void closeAllConnectors(IThreadContext threadContext)
    throws ManifoldCFException
  {
    // Go through the whole pool and clean it out
    synchronized (poolHash)
    {
      Iterator<Pool> iter = poolHash.values().iterator();
      while (iter.hasNext())
      {
        Pool p = iter.next();
        p.releaseAll(threadContext);
      }
    }
  }

  // Protected methods and classes
  
  protected String buildServiceTypeName(String connectionName)
  {
    return serviceTypePrefix + connectionName;
  }
  
  /** This class represents a value in the pool hash, which corresponds to a given key.
  */
  protected class Pool
  {
    protected final String serviceTypeName;
    protected final String serviceName;
    protected final List<T> stack = new ArrayList<T>();
    protected int numFree;

    /** Constructor
    */
    public Pool(IThreadContext threadContext, int maxCount, String connectionName)
      throws ManifoldCFException
    {
      this.numFree = maxCount;
      this.serviceTypeName = buildServiceTypeName(connectionName);
      // Now, register and activate service anonymously, and record the service name we get.
      ILockManager lockManager = LockManagerFactory.make(threadContext);
      this.serviceName = lockManager.registerServiceBeginServiceActivity(serviceTypeName, null, null);
    }

    /** Grab a connector.
    * If none exists, construct it using the information in the pool key.
    *@return the connector, or null if no connector could be connected.
    */
    public synchronized T getConnector(IThreadContext threadContext, String className, ConfigParams configParams)
      throws ManifoldCFException
    {
      // numFree represents the number of available connector instances that have not been given out at this moment.
      // So it's the max minus the pool count minus the number in use.
      while (numFree == 0)
      {
        try
        {
          wait();
        }
        catch (InterruptedException e)
        {
          throw new ManifoldCFException("Interrupted: "+e.getMessage(),e,ManifoldCFException.INTERRUPTED);
        }
      }

      // We decrement numFree when we hand out a connector instance; we increment numFree when we
      // throw away a connector instance from the pool.
      while (true)
      {
        if (stack.size() == 0)
        {
          T newrc = createConnectorInstance(threadContext,className);
          newrc.connect(configParams);
          stack.add(newrc);
        }
        
        // Since thread context set can fail, do that before we remove it from the pool.
        T rc = stack.remove(stack.size()-1);
        // Set the thread context.  This can throw an exception!!  We need to be sure our bookkeeping
        // is resilient against that possibility.  Losing a connector instance that was just sitting
        // in the pool does NOT affect numFree, so no change needed here; we just can't disconnect the
        // connector instance if this fails.
        rc.setThreadContext(threadContext);
        // Verify that the connector is in fact compatible
        if (!(rc.getClass().getName().equals(className) && rc.getConfiguration().equals(configParams)))
        {
          // Looks like parameters have changed, so discard old instance.
          try
          {
            rc.disconnect();
          }
          finally
          {
            rc.clearThreadContext();
          }
          continue;
        }
        // About to return a connector instance; decrement numFree accordingly.
        numFree--;
        return rc;
      }
    }

    /** Release a connector to the pool.
    *@param connector is the connector.
    */
    public synchronized void releaseConnector(T connector)
      throws ManifoldCFException
    {
      if (connector == null)
        return;

      // Make sure connector knows it's released
      connector.clearThreadContext();
      // Append
      stack.add(connector);
      numFree++;
      notifyAll();
    }

    /** Notify all free connectors.
    */
    public synchronized void pollAll(IThreadContext threadContext)
      throws ManifoldCFException
    {
      int i = 0;
      while (i < stack.size())
      {
        T rc = stack.get(i++);
        // Notify
        rc.setThreadContext(threadContext);
        try
        {
          rc.poll();
        }
        finally
        {
          rc.clearThreadContext();
        }
      }
    }

    /** Flush unused connectors.
    */
    public synchronized void flushUnused(IThreadContext threadContext)
      throws ManifoldCFException
    {
      while (stack.size() > 0)
      {
        // Disconnect
        T rc = stack.remove(stack.size()-1);
        rc.setThreadContext(threadContext);
        try
        {
          rc.disconnect();
        }
        finally
        {
          rc.clearThreadContext();
        }
      }
    }

    /** Release all free connectors.
    */
    public synchronized void releaseAll(IThreadContext threadContext)
      throws ManifoldCFException
    {
      flushUnused(threadContext);
      // End service activity
      ILockManager lockManager = LockManagerFactory.make(threadContext);
      lockManager.endServiceActivity(serviceTypeName, serviceName);
    }

  }

}
