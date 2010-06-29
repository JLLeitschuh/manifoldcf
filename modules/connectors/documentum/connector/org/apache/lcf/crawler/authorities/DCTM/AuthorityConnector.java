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
package org.apache.lcf.crawler.authorities.DCTM;

import org.apache.log4j.*;
import org.apache.lcf.core.interfaces.*;
import org.apache.lcf.agents.interfaces.*;
import org.apache.lcf.authorities.interfaces.*;
import org.apache.lcf.authorities.system.Logging;
import java.util.*;
import java.io.*;
import org.apache.lcf.crawler.common.DCTM.*;
import java.rmi.*;


/** Autheticator.
*/
public class AuthorityConnector extends org.apache.lcf.authorities.authorities.BaseAuthorityConnector
{

  public static final String CONFIG_PARAM_DOCBASE = "docbasename";
  public static final String CONFIG_PARAM_USERNAME = "docbaseusername";
  public static final String CONFIG_PARAM_PASSWORD = "docbasepassword";
  public static final String CONFIG_PARAM_DOMAIN = "domain";
  public static final String CONFIG_PARAM_CASEINSENSITIVE = "usernamecaseinsensitive";
  public static final String CONFIG_PARAM_USESYSTEMACLS = "usesystemacls";

  protected String docbaseName = null;
  protected String userName = null;
  protected String password = null;
  protected String domain = null;
  protected boolean caseInsensitive = false;
  protected boolean useSystemAcls = true;

  // Documentum has no "deny" tokens, and its document acls cannot be empty, so no local authority deny token is required.
  // However, it is felt that we need to be suspenders-and-belt, so here is the deny token.
  // The documentum tokens are of the form xxx:yyy, so they cannot collide with the standard deny token.
  protected static final String denyToken = "DEAD_AUTHORITY";

  protected static final AuthorizationResponse unreachableResponse = new AuthorizationResponse(new String[]{denyToken},AuthorizationResponse.RESPONSE_UNREACHABLE);
  protected static final AuthorizationResponse userNotFoundResponse = new AuthorizationResponse(new String[]{denyToken},AuthorizationResponse.RESPONSE_USERNOTFOUND);
  protected static final AuthorizationResponse userUnauthorizedResponse = new AuthorizationResponse(new String[]{denyToken},AuthorizationResponse.RESPONSE_USERUNAUTHORIZED);

  // This is the DFC session; it may be null, or it may be set.
  protected IDocumentum session = null;
  protected long lastSessionFetch = -1L;

  protected static final long timeToRelease = 300000L;

  public AuthorityConnector()
  {
    super();
  }

  protected class GetSessionThread extends Thread
  {
    protected Throwable exception = null;

    public GetSessionThread()
    {
      super();
      setDaemon(true);
    }

    public void run()
    {
      try
      {
        // Create a session
        IDocumentumFactory df = (IDocumentumFactory)Naming.lookup("rmi://127.0.0.1:8300/documentum_factory");
        IDocumentum newSession = df.make();
        newSession.createSession(docbaseName,userName,password,domain);
        session = newSession;
      }
      catch (Throwable e)
      {
        this.exception = e;
      }
    }

    public Throwable getException()
    {
      return exception;
    }
  }

  /** Get a DFC session.  This will be done every time it is needed.
  */
  protected void getSession()
    throws LCFException
  {
    if (session == null)
    {
      // This is the stuff that used to be in connect()
      if (docbaseName == null || docbaseName.length() < 1)
        throw new LCFException("Parameter "+CONFIG_PARAM_DOCBASE+" required but not set");

      if (Logging.authorityConnectors.isDebugEnabled())
        Logging.authorityConnectors.debug("DCTM: Docbase = '" + docbaseName + "'");

      if (userName == null || userName.length() < 1)
        throw new LCFException("Parameter "+CONFIG_PARAM_USERNAME+" required but not set");

      if (Logging.authorityConnectors.isDebugEnabled())
        Logging.authorityConnectors.debug("DCTM: Username = '" + userName + "'");

      if (password == null || password.length() < 1)
        throw new LCFException("Parameter "+CONFIG_PARAM_PASSWORD+" required but not set");

      Logging.authorityConnectors.debug("DCTM: Password exists");

      if (domain == null)
      {
        // Empty domain is allowed
        Logging.authorityConnectors.debug("DCTM: No domain");
      }
      else
        Logging.authorityConnectors.debug("DCTM: Domain = '" + domain + "'");

      if (caseInsensitive)
      {
        Logging.authorityConnectors.debug("DCTM: Case insensitivity enabled");
      }

      if (useSystemAcls)
      {
        Logging.authorityConnectors.debug("DCTM: Use system acls enabled");
      }


      // This actually sets up the connection
      GetSessionThread t = new GetSessionThread();
      try
      {
        t.start();
        t.join();
        Throwable thr = t.getException();
        if (thr != null)
        {
          if (thr instanceof java.net.MalformedURLException)
            throw (java.net.MalformedURLException)thr;
          else if (thr instanceof NotBoundException)
            throw (NotBoundException)thr;
          else if (thr instanceof RemoteException)
            throw (RemoteException)thr;
          else if (thr instanceof DocumentumException)
            throw (DocumentumException)thr;
          else
            throw (Error)thr;
        }
      }
      catch (InterruptedException e)
      {
        t.interrupt();
        throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
      }
      catch (java.net.MalformedURLException e)
      {
        throw new LCFException(e.getMessage(),e);
      }
      catch (NotBoundException e)
      {
        // Transient problem: Server not available at the moment.
        throw new LCFException("Server not up at the moment: "+e.getMessage(),e);
      }
      catch (RemoteException e)
      {
        Throwable e2 = e.getCause();
        if (e2 instanceof InterruptedException || e2 instanceof InterruptedIOException)
          throw new LCFException(e2.getMessage(),e2,LCFException.INTERRUPTED);
        session = null;
        lastSessionFetch = -1L;
        // Treat this as a transient problem
        throw new LCFException("Transient remote exception creating session: "+e.getMessage(),e);
      }
      catch (DocumentumException e)
      {
        // Base our treatment on the kind of error it is.
        if (e.getType() == DocumentumException.TYPE_SERVICEINTERRUPTION)
        {
          throw new LCFException("Remote service interruption creating session: "+e.getMessage(),e);
        }
        throw new LCFException(e.getMessage(),e);
      }
    }

    // Update the expire time for this session, except if an error was thrown.
    lastSessionFetch = System.currentTimeMillis();

  }

  /** Perform a DQL query, with appropriate reset on a remote exception */
  protected IDocumentumResult performDQLQuery(String query)
    throws DocumentumException, LCFException
  {
    while (true)
    {
      boolean noSession = (session==null);
      getSession();
      try
      {
        return session.performDQLQuery(query);
      }
      catch (RemoteException e)
      {
        if (noSession)
          throw new LCFException("Transient error connecting to documentum service",e);
        session = null;
        lastSessionFetch = -1L;
        continue;
      }
    }
  }

  protected class CheckConnectionThread extends Thread
  {
    protected Throwable exception = null;

    public CheckConnectionThread()
    {
      super();
      setDaemon(true);
    }

    public void run()
    {
      try
      {
        session.checkConnection();
      }
      catch (Throwable e)
      {
        this.exception = e;
      }
    }

    public Throwable getException()
    {
      return exception;
    }

  }

  /** Check connection, with appropriate retries */
  protected void checkConnection()
    throws DocumentumException, LCFException
  {
    while (true)
    {
      boolean noSession = (session==null);
      getSession();
      CheckConnectionThread t = new CheckConnectionThread();
      try
      {
        t.start();
        t.join();
        Throwable thr = t.getException();
        if (thr != null)
        {
          if (thr instanceof RemoteException)
            throw (RemoteException)thr;
          else if (thr instanceof DocumentumException)
            throw (DocumentumException)thr;
          else
            throw (Error)thr;
        }
        return;
      }
      catch (InterruptedException e)
      {
        t.interrupt();
        throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
      }
      catch (RemoteException e)
      {
        Throwable e2 = e.getCause();
        if (e2 instanceof InterruptedException || e2 instanceof InterruptedIOException)
          throw new LCFException(e2.getMessage(),e2,LCFException.INTERRUPTED);
        if (noSession)
          throw new LCFException("Transient error connecting to documentum service",e);
        session = null;
        lastSessionFetch = -1L;
        continue;
      }
    }
  }

  /** Perform getObjectByQualification, with appropriate reset */
  protected IDocumentumObject getObjectByQualification(String qualification)
    throws DocumentumException, LCFException
  {
    while (true)
    {
      boolean noSession = (session==null);
      getSession();
      try
      {
        return session.getObjectByQualification(qualification);
      }
      catch (RemoteException e)
      {
        if (noSession)
          throw new LCFException("Transient error connecting to documentum service",e);
        session = null;
        lastSessionFetch = -1L;
        continue;
      }
    }

  }

  /** Get server version, with appropriate retries */
  protected String getServerVersion()
    throws DocumentumException, LCFException
  {
    while (true)
    {
      boolean noSession = (session==null);
      getSession();
      try
      {
        return session.getServerVersion();
      }
      catch (RemoteException e)
      {
        if (noSession)
          throw new LCFException("Transient error connecting to documentum service",e);
        session = null;
        lastSessionFetch = -1L;
        continue;
      }
    }

  }


  protected class DestroySessionThread extends Thread
  {
    protected Throwable exception = null;

    public DestroySessionThread()
    {
      super();
      setDaemon(true);
    }

    public void run()
    {
      try
      {
        session.destroySession();
      }
      catch (Throwable e)
      {
        this.exception = e;
      }
    }

    public Throwable getException()
    {
      return exception;
    }

  }


  /** Release the session, if it's time.
  */
  protected void releaseCheck()
    throws LCFException
  {
    if (lastSessionFetch == -1L)
      return;

    long currentTime = System.currentTimeMillis();
    if (currentTime >= lastSessionFetch + timeToRelease)
    {
      DestroySessionThread t = new DestroySessionThread();
      try
      {
        t.start();
        t.join();
        Throwable thr = t.getException();
        if (thr != null)
        {
          if (thr instanceof RemoteException)
            throw (RemoteException)thr;
          else if (thr instanceof DocumentumException)
            throw (DocumentumException)thr;
          else
            throw (Error)thr;
        }
        session = null;
        lastSessionFetch = -1L;
      }
      catch (InterruptedException e)
      {
        t.interrupt();
        throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
      }
      catch (RemoteException e)
      {
        Throwable e2 = e.getCause();
        if (e2 instanceof InterruptedException || e2 instanceof InterruptedIOException)
          throw new LCFException(e2.getMessage(),e2,LCFException.INTERRUPTED);
        session = null;
        lastSessionFetch = -1L;
        // Treat this as a transient problem
        Logging.authorityConnectors.warn("Transient remote exception closing session",e);
      }
      catch (DocumentumException e)
      {
        // Base our treatment on the kind of error it is.
        if (e.getType() == DocumentumException.TYPE_SERVICEINTERRUPTION)
        {
          Logging.authorityConnectors.warn("Remote service interruption closing session",e);
        }
        else
          Logging.authorityConnectors.warn("Error closing session",e);
      }
    }
  }

  protected class GetUserAccessIDThread extends Thread
  {
    protected String strUserName;
    protected Throwable exception = null;
    protected String rval = null;
    protected AuthorizationResponse response = null;

    public GetUserAccessIDThread(String strUserName)
    {
      super();
      setDaemon(true);
      this.strUserName = strUserName;
    }

    public void run()
    {
      try
      {
        // Need the server version so we can figure out whether to try the user_login_name query
        String serverVersion = session.getServerVersion();
        boolean hasLoginNameColumn = (serverVersion.compareTo("5.3.") >= 0);

        IDocumentumObject object = null;
        try
        {
          if (hasLoginNameColumn)
            object = getObjectByQualification("dm_user where "+insensitiveMatch(caseInsensitive,"user_login_name",strUserName));
          if (!object.exists())
            object = getObjectByQualification("dm_user where "+insensitiveMatch(caseInsensitive,"user_os_name",strUserName));
          if (!object.exists())
          {
            if (Logging.authorityConnectors.isDebugEnabled())
              Logging.authorityConnectors.debug("DCTM: No user found for username '" + strUserName + "'");
            response = userNotFoundResponse;
            return;
          }

          if (object.getUserState() != 0)
          {
            if (Logging.authorityConnectors.isDebugEnabled())
              Logging.authorityConnectors.debug("DCTM: User found for username '" + strUserName + "' but the account is not active.");
            response = userUnauthorizedResponse;
            return;
          }

          rval = object.getUserName();

        }
        finally
        {
          if (object != null)
            object.release();
        }


      }
      catch (Throwable e)
      {
        this.exception = e;
      }
    }

    public Throwable getException()
    {
      return exception;
    }

    public String getUserID()
    {
      return rval;
    }

    public AuthorizationResponse getResponse()
    {
      return response;
    }
  }

  protected class GetAccessTokensThread extends Thread
  {
    protected String strDQL;
    protected ArrayList list;
    protected Throwable exception = null;

    public GetAccessTokensThread(String strDQL, ArrayList list)
    {
      super();
      setDaemon(true);
      this.strDQL = strDQL;
      this.list = list;
    }

    public void run()
    {
      try
      {
        IDocumentumResult result = session.performDQLQuery(strDQL);
        try
        {
          if (Logging.authorityConnectors.isDebugEnabled())
            Logging.authorityConnectors.debug("DCTM: Collection returned.");
          while (result.isValidRow())
          {
            String strObjectName = result.getStringValue("object_name");
            String strOwnerName = result.getStringValue("owner_name");
            String strFullTokenName = docbaseName + ":" + strOwnerName + "." + strObjectName;
            list.add(strFullTokenName);

            if (Logging.authorityConnectors.isDebugEnabled())
              Logging.authorityConnectors.debug("DCTM: ACL being added: " + strFullTokenName);

            result.nextRow();

          }
        }
        finally
        {
          result.close();
        }


      }
      catch (Throwable e)
      {
        this.exception = e;
      }
    }

    public Throwable getException()
    {
      return exception;
    }
  }

  /** Obtain the access tokens for a given user name.
  *@param strUserNamePassedIn is the user name or identifier.
  *@return the response tokens (according to the current authority).
  * (Should throws an exception only when a condition cannot be properly described within the authorization response object.)
  */
  public AuthorizationResponse getAuthorizationResponse(String strUserNamePassedIn)
    throws LCFException
  {

    if (Logging.authorityConnectors.isDebugEnabled())
      Logging.authorityConnectors.debug("DCTM: Inside AuthorityConnector.getAuthorizationResponse for user '"+strUserNamePassedIn+"'");

    try
    {
      String strUserName;

      // Strip off domain from username passed in
      int intDomainLoc = strUserNamePassedIn.indexOf("@");
      if (intDomainLoc > 1)
      {
        strUserName = strUserNamePassedIn.substring(0, intDomainLoc);
      }
      else
      {
        strUserName = strUserNamePassedIn;
      }

      String strAccessToken;
      while (true)
      {
        boolean noSession = (session==null);
        getSession();
        GetUserAccessIDThread t = new GetUserAccessIDThread(strUserName);
        try
        {
          t.start();
          t.join();
          Throwable thr = t.getException();
          if (thr != null)
          {
            if (thr instanceof RemoteException)
              throw (RemoteException)thr;
            else if (thr instanceof DocumentumException)
              throw (DocumentumException)thr;
            else
              throw (Error)thr;
          }
          if (t.getResponse() != null)
            return t.getResponse();
          strAccessToken = t.getUserID();
          // Exit out of retry loop
          break;
        }
        catch (InterruptedException e)
        {
          t.interrupt();
          throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
        }
        catch (RemoteException e)
        {
          Throwable e2 = e.getCause();
          if (e2 instanceof InterruptedException || e2 instanceof InterruptedIOException)
            throw new LCFException(e2.getMessage(),e2,LCFException.INTERRUPTED);
          if (noSession)
          {
            Logging.authorityConnectors.warn("DCTM: Transient error checking authorization: "+e.getMessage(),e);
            return unreachableResponse;
          }
          session = null;
          lastSessionFetch = -1L;
          // Go back around again
        }
      }


      // String strDQL = "SELECT DISTINCT A.object_name FROM dm_acl A
      // WHERE (any (A.r_accessor_name = '" + strAccessToken + "' AND
      // A.r_accessor_permit > 1) OR ANY (A.r_accessor_name in (SELECT
      // G.group_name FROM dm_group G WHERE ANY G.i_all_users_names = '" +
      // strAccessToken + "') AND A.r_accessor_permit > 1)) AND '" +
      // strAccessToken + "' In (SELECT U.user_name FROM dm_user U WHERE
      // U.user_state=0)";
      String strDQL = "SELECT DISTINCT A.owner_name, A.object_name FROM dm_acl A " + " WHERE ";
      if (!useSystemAcls)
        strDQL += "A.object_name NOT LIKE 'dm_%' AND (";
      strDQL += "(any (A.r_accessor_name IN ('" + strAccessToken + "', 'dm_world') AND r_accessor_permit>2) "
      + " OR (any (A.r_accessor_name='dm_owner' AND A.r_accessor_permit>2) AND A.owner_name=" + quoteDQLString(strAccessToken) + ")"
      + " OR (ANY (A.r_accessor_name in (SELECT G.group_name FROM dm_group G WHERE ANY G.i_all_users_names = " + quoteDQLString(strAccessToken) + ")"
      + " AND r_accessor_permit>2))"
      + ")";
      if (!useSystemAcls)
        strDQL += ")";

      if (Logging.authorityConnectors.isDebugEnabled())
        Logging.authorityConnectors.debug("DCTM: About to execute query= (" + strDQL + ")");

      while (true)
      {
        boolean noSession = (session==null);
        getSession();
        ArrayList list =  new ArrayList();
        GetAccessTokensThread t = new GetAccessTokensThread(strDQL,list);
        try
        {
          t.start();
          t.join();
          Throwable thr = t.getException();
          if (thr != null)
          {
            if (thr instanceof RemoteException)
              throw (RemoteException)thr;
            else if (thr instanceof DocumentumException)
              throw (DocumentumException)thr;
            else
              throw (Error)thr;
          }
          Logging.authorityConnectors.debug("DCTM: Done processing authorization query");

          String[] strArrayRetVal = new String[list.size()];

          int intObjectIdIndex = 0;

          while (intObjectIdIndex < strArrayRetVal.length)
          {
            strArrayRetVal[intObjectIdIndex] = (String)list.get(intObjectIdIndex);
            intObjectIdIndex++;
          }
          // Break out of retry loop and return
          return new AuthorizationResponse(strArrayRetVal,AuthorizationResponse.RESPONSE_OK);
        }
        catch (InterruptedException e)
        {
          t.interrupt();
          throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
        }
        catch (RemoteException e)
        {
          Throwable e2 = e.getCause();
          if (e2 instanceof InterruptedException || e2 instanceof InterruptedIOException)
            throw new LCFException(e2.getMessage(),e2,LCFException.INTERRUPTED);
          if (noSession)
          {
            Logging.authorityConnectors.warn("DCTM: Transient error checking authorization: "+e.getMessage(),e);
            return unreachableResponse;
          }
          session = null;
          lastSessionFetch = -1L;
          // Go back around again
        }
      }
    }
    catch (DocumentumException e)
    {

      if (e.getType() == DocumentumException.TYPE_SERVICEINTERRUPTION)
      {
        Logging.authorityConnectors.warn("DCTM: Transient error checking authorization: "+e.getMessage(),e);
        // Transient: Treat as if user does not exist, not like credentials invalid.
        return unreachableResponse;
      }
      throw new LCFException(e.getMessage(),e);
    }
  }

  /** Obtain the default access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the default response tokens, presuming that the connect method fails.
  */
  public AuthorizationResponse getDefaultAuthorizationResponse(String userName)
  {
    return unreachableResponse;
  }

  protected static String insensitiveMatch(boolean insensitive, String field, String value)
  {
    StringBuffer sb = new StringBuffer();
    if (insensitive)
      sb.append("upper(").append(field).append(")");
    else
      sb.append(field);
    sb.append("=");
    if (insensitive)
      sb.append(quoteDQLString(value.toUpperCase()));
    else
      sb.append(quoteDQLString(value));
    return sb.toString();
  }

  protected static String quoteDQLString(String value)
  {
    StringBuffer sb = new StringBuffer("'");
    int i = 0;
    while (i < value.length())
    {
      char x = value.charAt(i++);
      if (x == '\'')
        sb.append(x);
      sb.append(x);
    }
    sb.append("'");
    return sb.toString();
  }

  /** Test the connection.  Returns a string describing the connection integrity.
  *@return the connection's status as a displayable string.
  */
  public String check()
    throws LCFException
  {
    try
    {
      try
      {
        checkConnection();
        return super.check();
      }
      catch (DocumentumException e)
      {
        // Base our treatment on the kind of error it is.
        if (e.getType() == DocumentumException.TYPE_SERVICEINTERRUPTION)
          return "Connection temporarily failed: "+e.getMessage();
        else
          return "Connection failed: "+e.getMessage();
      }
    }
    catch (LCFException e)
    {
      return "Connection failed: "+e.getMessage();
    }

  }


  public void connect(ConfigParams configParams)
  {
    super.connect(configParams);

    docbaseName = configParams.getParameter(CONFIG_PARAM_DOCBASE);
    userName = configParams.getParameter(CONFIG_PARAM_USERNAME);
    password = configParams.getObfuscatedParameter(CONFIG_PARAM_PASSWORD);
    domain = configParams.getParameter(CONFIG_PARAM_DOMAIN);
    if (domain == null || domain.length() < 1)
    {
      // Empty domain is allowed
      domain = null;
    }

    String strCaseInsensitive = configParams.getParameter(CONFIG_PARAM_CASEINSENSITIVE);
    if (strCaseInsensitive != null && strCaseInsensitive.equals("true"))
    {
      caseInsensitive = true;
    }
    else
      caseInsensitive = false;

    String strUseSystemAcls = configParams.getParameter(CONFIG_PARAM_USESYSTEMACLS);
    if (strUseSystemAcls == null || strUseSystemAcls.equals("true"))
    {
      useSystemAcls = true;
    }
    else
      useSystemAcls = false;

  }

  /** Disconnect from Documentum.
  */
  public void disconnect()
    throws LCFException
  {
    if (session != null)
    {
      DestroySessionThread t = new DestroySessionThread();
      try
      {
        t.start();
        t.join();
        Throwable thr = t.getException();
        if (thr != null)
        {
          if (thr instanceof RemoteException)
            throw (RemoteException)thr;
          else if (thr instanceof DocumentumException)
            throw (DocumentumException)thr;
          else
            throw (Error)thr;
        }
        session = null;
        lastSessionFetch = -1L;
      }
      catch (InterruptedException e)
      {
        t.interrupt();
        throw new LCFException("Interrupted: "+e.getMessage(),e,LCFException.INTERRUPTED);
      }
      catch (RemoteException e)
      {
        Throwable e2 = e.getCause();
        if (e2 instanceof InterruptedException || e2 instanceof InterruptedIOException)
          throw new LCFException(e2.getMessage(),e2,LCFException.INTERRUPTED);
        session = null;
        lastSessionFetch = -1L;
        // Treat this as a transient problem
        Logging.authorityConnectors.warn("DCTM: Transient remote exception closing session: "+e.getMessage(),e);
      }
      catch (DocumentumException e)
      {
        // Base our treatment on the kind of error it is.
        if (e.getType() == DocumentumException.TYPE_SERVICEINTERRUPTION)
        {
          Logging.authorityConnectors.warn("DCTM: Remote service interruption closing session: "+e.getMessage(),e);
        }
        else
          Logging.authorityConnectors.warn("DCTM: Error closing session: "+e.getMessage(),e);
      }

    }

    docbaseName = null;
    userName = null;
    password = null;
    domain = null;
  }

  public String getJSPFolder()
  {
    return "DCTM";
  }

  /** This method is periodically called for all connectors that are connected but not
  * in active use.
  */
  public void poll()
    throws LCFException
  {
    releaseCheck();
  }

  // UI support methods.
  //
  // These support methods are involved in setting up authority connection configuration information. The configuration methods cannot assume that the
  // current authority object is connected.  That is why they receive a thread context argument.
    
  /** Output the configuration header section.
  * This method is called in the head section of the connector's configuration page.  Its purpose is to add the required tabs to the list, and to output any
  * javascript methods that might be needed by the configuration editing HTML.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out, ConfigParams parameters, ArrayList tabsArray)
    throws LCFException, IOException
  {
    tabsArray.add("Docbase");
    tabsArray.add("User Mapping");
    tabsArray.add("System ACLs");
    out.print(
"<script type=\"text/javascript\">\n"+
"<!--\n"+
"function checkConfigForSave()\n"+
"{\n"+
"  if (editconnection.docbasename.value == \"\")\n"+
"  {\n"+
"    alert(\"Please supply the name of a Docbase\");\n"+
"    SelectTab(\"Docbase\");\n"+
"    editconnection.docbasename.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.docbaseusername.value == \"\")\n"+
"  {\n"+
"    alert(\"The connection requires a valid Documentum user name\");\n"+
"    SelectTab(\"Docbase\");\n"+
"    editconnection.docbaseusername.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.docbasepassword.value == \"\")\n"+
"  {\n"+
"    alert(\"The connection requires the Documentum user's password\");\n"+
"    SelectTab(\"Docbase\");\n"+
"    editconnection.docbasepassword.focus();\n"+
"    return false;\n"+
"  }\n"+
"  return true;\n"+
"}\n"+
"\n"+
"//-->\n"+
"</script>\n"
    );
  }
  
  /** Output the configuration body section.
  * This method is called in the body section of the authority connector's configuration page.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html>, <body>, and <form> tags.  The name of the
  * form is "editconnection".
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabName is the current tab name.
  */
  public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out, ConfigParams parameters, String tabName)
    throws LCFException, IOException
  {
    String docbaseName = parameters.getParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_DOCBASE);
    if (docbaseName == null)
      docbaseName = "";

    String docbaseUserName = parameters.getParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_USERNAME);
    if (docbaseUserName == null)
      docbaseUserName = "";

    String docbasePassword = parameters.getObfuscatedParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_PASSWORD);
    if (docbasePassword == null)
      docbasePassword = "";

    String docbaseDomain = parameters.getParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_DOMAIN);
    if (docbaseDomain == null)
      docbaseDomain = "";

    String caseInsensitiveUser = parameters.getParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_CASEINSENSITIVE);
    if (caseInsensitiveUser == null)
      caseInsensitiveUser = "false";

    String useSystemAcls = parameters.getParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_USESYSTEMACLS);
    if (useSystemAcls == null)
      useSystemAcls = "true";

    // "Docbase" tab
    if (tabName.equals("Docbase"))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Docbase name:</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"32\" name=\"docbasename\" value=\""+org.apache.lcf.ui.util.Encoder.attributeEscape(docbaseName)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Docbase user name:</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"32\" name=\"docbaseusername\" value=\""+org.apache.lcf.ui.util.Encoder.attributeEscape(docbaseUserName)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Docbase password:</nobr></td>\n"+
"    <td class=\"value\"><input type=\"password\" size=\"32\" name=\"docbasepassword\" value=\""+org.apache.lcf.ui.util.Encoder.attributeEscape(docbasePassword)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Docbase domain:</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"32\" name=\"docbasedomain\" value=\""+org.apache.lcf.ui.util.Encoder.attributeEscape(docbaseDomain)+"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for "Docbase" tab
      out.print(
"<input type=\"hidden\" name=\"docbasename\" value=\""+org.apache.lcf.ui.util.Encoder.attributeEscape(docbaseName)+"\"/>\n"+
"<input type=\"hidden\" name=\"docbaseusername\" value=\""+org.apache.lcf.ui.util.Encoder.attributeEscape(docbaseUserName)+"\"/>\n"+
"<input type=\"hidden\" name=\"docbasepassword\" value=\""+org.apache.lcf.ui.util.Encoder.attributeEscape(docbasePassword)+"\"/>\n"+
"<input type=\"hidden\" name=\"docbasedomain\" value=\""+org.apache.lcf.ui.util.Encoder.attributeEscape(docbaseDomain)+"\"/>\n"
      );
    }

    // "User Mapping" tab
    if (tabName.equals("User Mapping"))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Authentication username matching:</nobr></td>\n"+
"    <td class=\"value\">\n"+
"      <table class=\"displaytable\">\n"+
"        <tr>\n"+
"          <td class=\"description\"><input name=\"usernamecaseinsensitive\" type=\"radio\" value=\"true\" "+((caseInsensitiveUser.equals("true"))?"checked=\"true\"":"")+" /></td>\n"+
"          <td class=\"value\"><nobr>Case insensitive</nobr></td>\n"+
"        </tr>\n"+
"        <tr>\n"+
"          <td class=\"description\"><input name=\"usernamecaseinsensitive\" type=\"radio\" value=\"false\" "+((!caseInsensitiveUser.equals("true"))?"checked=\"true\"":"")+" /></td>\n"+
"          <td class=\"value\"><nobr>Case sensitive</nobr></td>\n"+
"        </tr>\n"+
"      </table>\n"+
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for "User Mapping" tab
      out.print(
"<input type=\"hidden\" name=\"usernamecaseinsensitive\" value=\""+caseInsensitiveUser+"\"/>\n"
      );
    }

    // "System ACLs" tab
    if (tabName.equals("System ACLs"))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>Use system acls:</nobr></td>\n"+
"    <td class=\"value\">\n"+
"      <table class=\"displaytable\">\n"+
"        <tr>\n"+
"          <td class=\"description\"><input name=\"usesystemacls\" type=\"radio\" value=\"true\" "+((useSystemAcls.equals("true"))?"checked=\"true\"":"")+" /></td>\n"+
"          <td class=\"value\"><nobr>Use system acls</nobr></td>\n"+
"        </tr>\n"+
"        <tr>\n"+
"          <td class=\"description\"><input name=\"usesystemacls\" type=\"radio\" value=\"false\" "+((!useSystemAcls.equals("true"))?"checked=\"true\"":"")+" /></td>\n"+
"          <td class=\"value\"><nobr>Don't use system acls</nobr></td>\n"+
"        </tr>\n"+
"      </table>\n"+
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for "System ACLs" tab
      out.print(
"<input type=\"hidden\" name=\"usesystemacls\" value=\""+useSystemAcls+"\"/>\n"
      );
    }
  }
  
  /** Process a configuration post.
  * This method is called at the start of the authority connector's configuration page, whenever there is a possibility that form data for a connection has been
  * posted.  Its purpose is to gather form information and modify the configuration parameters accordingly.
  * The name of the posted form is "editconnection".
  *@param threadContext is the local thread context.
  *@param variableContext is the set of variables available from the post, including binary file post information.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of the connection (and cause a redirection to an error page).
  */
  public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext, ConfigParams parameters)
    throws LCFException
  {
    String docbaseName = variableContext.getParameter("docbasename");
    if (docbaseName != null)
      parameters.setParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_DOCBASE,docbaseName);
	
    String docbaseUserName = variableContext.getParameter("docbaseusername");
    if (docbaseUserName != null)
      parameters.setParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_USERNAME,docbaseUserName);
	
    String docbasePassword = variableContext.getParameter("docbasepassword");
    if (docbasePassword != null)
      parameters.setObfuscatedParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_PASSWORD,docbasePassword);
	
    String docbaseDomain = variableContext.getParameter("docbasedomain");
    if (docbaseDomain != null)
      parameters.setParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_DOMAIN,docbaseDomain);

    String caseInsensitiveUser = variableContext.getParameter("usernamecaseinsensitive");
    if (caseInsensitiveUser != null)
      parameters.setParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_CASEINSENSITIVE,caseInsensitiveUser);

    String useSystemAcls = variableContext.getParameter("usesystemacls");
    if (useSystemAcls != null)
      parameters.setParameter(org.apache.lcf.crawler.authorities.DCTM.AuthorityConnector.CONFIG_PARAM_USESYSTEMACLS,useSystemAcls);
    
    return null;
  }
  
  /** View configuration.
  * This method is called in the body section of the authority connector's view configuration page.  Its purpose is to present the connection information to the user.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html> and <body> tags.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  */
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out, ConfigParams parameters)
    throws LCFException, IOException
  {
    out.print(
"<table class=\"displaytable\">\n"+
"  <tr>\n"+
"    <td class=\"description\" colspan=\"1\"><nobr>Parameters:</nobr></td>\n"+
"    <td class=\"value\" colspan=\"3\">\n"
    );
    Iterator iter = parameters.listParameters();
    while (iter.hasNext())
    {
      String param = (String)iter.next();
      String value = parameters.getParameter(param);
      if (param.length() >= "password".length() && param.substring(param.length()-"password".length()).equalsIgnoreCase("password"))
      {
        out.print(
"      <nobr>"+org.apache.lcf.ui.util.Encoder.bodyEscape(param)+"=********</nobr><br/>\n"
        );
      }
      else if (param.length() >="keystore".length() && param.substring(param.length()-"keystore".length()).equalsIgnoreCase("keystore"))
      {
        IKeystoreManager kmanager = KeystoreManagerFactory.make("",value);
        out.print(
"      <nobr>"+org.apache.lcf.ui.util.Encoder.bodyEscape(param)+"=<"+Integer.toString(kmanager.getContents().length)+" certificate(s)></nobr><br/>\n"
        );
      }
      else
      {
        out.print(
"      <nobr>"+org.apache.lcf.ui.util.Encoder.bodyEscape(param)+"="+org.apache.lcf.ui.util.Encoder.bodyEscape(value)+"</nobr><br/>\n"
        );
      }
    }
    out.print(
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
    );
  }

}
