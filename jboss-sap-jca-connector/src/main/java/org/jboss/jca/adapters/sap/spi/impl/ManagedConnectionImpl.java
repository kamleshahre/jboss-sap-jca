/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.jca.adapters.sap.spi.impl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import javax.resource.ResourceException;
import javax.resource.spi.ConnectionEvent;
import javax.resource.spi.ConnectionEventListener;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.DissociatableManagedConnection;
import javax.resource.spi.LocalTransaction;
import javax.resource.spi.ManagedConnectionMetaData;
import javax.resource.spi.security.PasswordCredential;
import javax.security.auth.Subject;
import javax.transaction.xa.XAResource;

import org.jboss.jca.adapters.sap.cci.Connection;
import org.jboss.jca.adapters.sap.cci.impl.ConnectionImpl;
import org.jboss.jca.adapters.sap.spi.ManagedConnection;
import org.jboss.jca.adapters.sap.spi.ManagedConnectionFactory;

import com.sap.conn.jco.JCoContext;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.ext.DestinationDataProvider;

/**
 * Implements the {@link ManagedConnection } and {@link DissociatableManagedConnection } interfaces for the JBoss SAP JCA
 * Connector.
 * 
 * @author William Collins
 * 
 * @version $Id: 4262e7ef5d638b4d5ebcd58a10f6fbe2cf209048 $
 */
public class ManagedConnectionImpl implements ManagedConnection {
	
	/**
	 * Remote function module called to commit transaction
	 */
	private static final String COMMIT_FUNCTION = "BAPI_TRANSACTION_COMMIT";
	
	/**
	 * Name of import variable set in remote function call to commit transaction that causes synchronous commit 
	 */
	private static final String SYNC_COMMIT_PARAM = "WAIT";

	/**
	 * Value pass in remote function call to commit transaction that causes synchronous commit.
	 */
	private static final String SYNC_COMMIT_PARAM_VAL = "X";

	/**
	 * Remote function module called to rollback transaction
	 */
	private static final String ROLLBACK_FUNCTION = "BAPI_TRANSACTION_ROLLBACK";
	
	/**
	 * States of a manage connection.
	 */
	public static enum State {
		ACTIVE,		/* Valid managed connection with active physical connection to SAP system */ 
		DESTROYED;	/* Invalid managed connection */
	}
	
	/** 
	 * The logwriter set by application server 
	 */
	private PrintWriter logwriter;

	/** 
	 * The factory this managed connection is associated with 
	 */
	private ManagedConnectionFactoryImpl managedConnectionFactory;

	/** 
	 * The application server call backs observing this connection 
	 */
	private List<ConnectionEventListener> listeners;

	/** 
	 * Set of active connection handles associated with this managed connection 
	 */
	private final Set<Connection> handles = new HashSet<Connection>();

	/** 
	 * Unique name of destination associated with this managed connection in JCo runtime 
	 */
	private final String destinationName;
	
	/** 
	 * Physical connection handle to SAP system in JCo runtime
	 */
	private JCoDestination destination;
	
	/** 
	 * Meta data describing this managed connection 
	 */
	private ManagedConnectionMetaDataImpl connectionMetaData = null;

	/** 
	 * State of connection.
	 * 
	 * Managed connection starts in <code>ACTIVE</code> state when created and transitions to final <code>DESTROYED</code> state 
	 * when destroyed by application server. 
	 */
	private State state = State.ACTIVE;
	
	/**
	 * The local transaction object that enables application server and clients to manage connections transaction context.
	 */
	private final LocalTransactionImpl localTransaction = new LocalTransactionImpl(this);
	
	/**
	 * Flag indicating whether this managed connection is in transaction.
	 */
	private boolean inTransaction = false;
	
	/**
	 * Construct a managed connection with specified connection request info and associated with the specified managed
	 * connection factory.
	 * 
	 * @param mcf - the associated managed connection factory
	 * @param connectionRequestInfo - the connection request info configuration
	 * @throws ResourceException
	 */
	public ManagedConnectionImpl(ManagedConnectionFactoryImpl mcf, ConnectionRequestInfoImpl connectionRequestInfo)
			throws ResourceException {
		this.managedConnectionFactory = mcf;
		this.logwriter = null;
		this.listeners = Collections.synchronizedList(new ArrayList<ConnectionEventListener>(1));

		// Create unique destination name for destination property configuration. 
		destinationName = UUID.randomUUID().toString();
		
		// Make destination configuration available to JCo runtime
		this.managedConnectionFactory.getResourceAdapter().getDestinationDataProvider()
				.addDestinationProperties(destinationName, connectionRequestInfo);
		
		// Attempt to connect to SAP system.
		try {
			this.destination = JCoDestinationManager.getDestination(destinationName);
			if (connectionRequestInfo.getPingOnCreate().equals("true"))
				this.destination.ping();
		} catch (JCoException e) {
			this.destroy();
			throw ExceptionBundle.EXCEPTIONS.failedToCreateManagedConnection(e);
		}

		this.managedConnectionFactory.associateConnection(this);
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public int hashCode() {
		return destination.getProperties().hashCode();
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean equals(Object other) {
		if (other == null)
			return false;
		if (other == this)
			return true;
		if (!(other instanceof ManagedConnectionImpl))
			return false;
		ManagedConnectionImpl obj = (ManagedConnectionImpl) other;
		return destination.getProperties().equals(obj.destination.getProperties());
	}

	/**
	 * {@inheritDoc}
	 */
	public Object getConnection(Subject subject, ConnectionRequestInfo cxRequestInfo) throws ResourceException {
		checkState();
		if (cxRequestInfo != null && !(cxRequestInfo instanceof ConnectionRequestInfoImpl))
			throw new ResourceException("managed-connection-impl-invalid-connection-request-info-type");

		// Validate subject credentials match those of this connection.
		if (subject != null) {
			boolean foundMatchingCredential = false;
			searchPrivateCredentials: for (PasswordCredential credential : subject.getPrivateCredentials(PasswordCredential.class)) {
				if (credential.getManagedConnectionFactory().equals(this)) {
					if (!getProperties().getUserName()
							.equals(credential.getUserName())) {
						continue searchPrivateCredentials;
					} else if (!getProperties().getPassword()
							.equals(new String(credential.getPassword()))) {
						continue searchPrivateCredentials;
					}
					// Found matching credentials.
					foundMatchingCredential = true;
					break searchPrivateCredentials;
				}
			}
			if (!foundMatchingCredential) 
				throw ExceptionBundle.EXCEPTIONS.failedToFindMatchingSecurityCredentialsInSubject();
		}
		
		if (cxRequestInfo != null) {
		ConnectionRequestInfoImpl jCxRequestInfo = (ConnectionRequestInfoImpl) cxRequestInfo;
			searchConnectionRequestProperties: for (Entry<Object, Object> entry : jCxRequestInfo.entrySet()) {
				if (subject != null
						&& (entry.getKey().equals(DestinationDataProvider.JCO_USER) || entry.getKey().equals(
								DestinationDataProvider.JCO_PASSWD)))
					// Already checked management credentials against subject credentials which override
					// connection request info credentails.
					continue searchConnectionRequestProperties;
	
				if (!getProperties().get(entry.getKey()).equals(entry.getValue()))
					throw ExceptionBundle.EXCEPTIONS.connectionRequestInfoParameterDoesNotMatch(entry.getValue().toString(), entry.getKey().toString(), getProperties().get(entry.getKey()).toString());
			}
		}

		Connection connection = new ConnectionImpl(this);

		return connection;
	}

	/**
	 * {@inheritDoc}
	 */
	public void associateConnection(Object connection) throws ResourceException {
		checkState();
		if (!(connection instanceof ConnectionImpl))
			throw ExceptionBundle.EXCEPTIONS.invalidConnectionTypeAssociatedWithManagedConnection(connection == null ? "null": connection.getClass().getName());

		ConnectionImpl cciConnection = (ConnectionImpl) connection;
		cciConnection.associateManagedConnection(this);
	}

	/**
	 * {@inheritDoc}
	 */
	public void dissociateConnections() throws ResourceException {
		checkState();

		Collection<Connection> copy = null;
		synchronized (handles) {
			if (handles.size() > 0)
				copy = new HashSet<Connection>(handles);
		}

		if (copy != null) {
			for (Connection cciConnection : copy) {
				((ConnectionImpl)cciConnection).dissociateManagedConnection();
			}
		}

	}

	/**
	 * {@inheritDoc}
	 */
	public void cleanup() throws ResourceException
	{
		Collection<Connection> copy = null;
		synchronized (handles) {
			if (handles.size() > 0)
				copy = new HashSet<Connection>(handles);
		}

		if (copy != null) {
			for (Connection cciConnection : copy) {
				cciConnection.close();
			}
		}
		
		// End any stateful session.
		internalEndStatefulSession();
	}

	/**
	 * {@inheritDoc}
	 */
	public void destroy() throws ResourceException {
		synchronized (state) {
			if (state == State.DESTROYED)
				return;
			state = State.DESTROYED;
		}

		// Cleanup any outstanding connection handles.
		cleanup();
		
		this.managedConnectionFactory.dissociateConnection(this);
		
		// Remove destination configuration from JCo runtime
		this.managedConnectionFactory.getResourceAdapter().getDestinationDataProvider()
				.removeDestinationProperties(destinationName);
		
		managedConnectionFactory = null;
		destination = null;
	}

	/**
	 * {@inheritDoc}
	 */
	public void addConnectionEventListener(ConnectionEventListener listener) {
		if (listener == null)
			throw ExceptionBundle.EXCEPTIONS.connectionEventListenerIsNull();
		synchronized (listeners) {
			listeners.add(listener);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public void removeConnectionEventListener(ConnectionEventListener listener) {
		if (listener == null)
			throw ExceptionBundle.EXCEPTIONS.connectionEventListenerIsNull();
		synchronized (listeners) {
			listeners.remove(listener);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public PrintWriter getLogWriter() throws ResourceException {
		return logwriter;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setLogWriter(PrintWriter out) throws ResourceException {
		logwriter = out;
	}

	/**
	 * {@inheritDoc}
	 */
	public LocalTransaction getLocalTransaction() throws ResourceException {
		return localTransaction;
	}

	/**
	 * {@inheritDoc}
	 */
	public XAResource getXAResource() throws ResourceException {
		throw ExceptionBundle.EXCEPTIONS.xaResourceIsNotSupported();
	}

	/**
	 * {@inheritDoc}
	 */
	public ManagedConnectionMetaData getMetaData() throws ResourceException {
		checkState();
		if (connectionMetaData == null) 
			connectionMetaData = new ManagedConnectionMetaDataImpl(destination); 
		return connectionMetaData;
	}
	
	/**
	 * Begins stateful session in SAP system and informs application server that transaction has begun.
	 * 
	 * @throws ResourceException If connection has already begun a transaction.
	 */
	synchronized void beginLocalTransaction() throws ResourceException {
		checkState();
		
		if (inTransaction) 
			// JCA 1.5 Specification: 7.8.3
			throw ExceptionBundle.EXCEPTIONS.connectionHasOutstandingTransaction();
		inTransaction = true;
		
		// Start stateful session for transaction
		beginStatefulSession();

		// Notify connection event listeners of local transaction start.
		Collection<ConnectionEventListener> copy = null;
		synchronized (listeners) {
			if (listeners != null && listeners.size() > 0)
				copy = new ArrayList<ConnectionEventListener>(listeners);
		}
		if (copy != null) {
			// JCA 1.5 Specification: 7.7.2.1
			ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.LOCAL_TRANSACTION_STARTED);
			for (ConnectionEventListener cel : copy) {
				cel.localTransactionStarted(event);
			}
		}
		
	}
	
	/**
	 * Invokes commit RFM in SAP system, ends stateful session and informs application server that transaction has committed. 
	 * 
	 * @throws ResourceException If connection has not begun a transaction or error occurs in JCo runtime.
	 */
	synchronized void commitLocalTransaction() throws ResourceException {
		checkState();
		
		if (!inTransaction) 
			// JCA 1.5 Specification: 7.8.3
			throw ExceptionBundle.EXCEPTIONS.connectionHasNoOutstandingTransaction();
		inTransaction = false;
		
		// Commit transaction synchronously in SAP system
		try {
			JCoFunction commit = destination.getRepository().getFunction(COMMIT_FUNCTION);
			commit.getImportParameterList().setValue(SYNC_COMMIT_PARAM, SYNC_COMMIT_PARAM_VAL);
			commit.execute(destination);
		} catch (JCoException e) {
			// Note transaction should be implicitly rolled back in SAP system on error.
			throw ExceptionBundle.EXCEPTIONS.commitFailed(e);
		} finally {
			// End stateful session for transaction.
			endStatefulSession();
		}
		
		// Notify connection event listeners of local transaction commit.
		Collection<ConnectionEventListener> copy = null;
		synchronized (listeners) {
			if (listeners != null && listeners.size() > 0)
				copy = new ArrayList<ConnectionEventListener>(listeners);
		}
		if (copy != null) {
			// JCA 1.5 Specification: 7.7.2.1
			ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.LOCAL_TRANSACTION_COMMITTED);
			for (ConnectionEventListener cel : copy) {
				cel.localTransactionCommitted(event);
			}
		}
		
		
	}
	
	/**
	 * Invokes rollback RFM in SAP system, ends stateful session and informs application server that transaction has rolled back. 
	 * 
	 * @throws ResourceException If connection has not begun a transaction or error occurs in JCo runtime.
	 */
	synchronized void rollbackLocalTransaction() throws ResourceException {
		checkState();
		
		if (!inTransaction) 
			// JCA 1.5 Specification: 7.8.3
			throw ExceptionBundle.EXCEPTIONS.connectionHasNoOutstandingTransaction();
		inTransaction = false;
		
		// Commit transaction synchronously in SAP system
		try {
			JCoFunction rollback = destination.getRepository().getFunction(ROLLBACK_FUNCTION);
			rollback.execute(destination);
		} catch (JCoException e) {
			// Note transaction should be implicitly rolled back in SAP system on error.
			throw ExceptionBundle.EXCEPTIONS.rollBackFailed(e);
		} finally {
			// End stateful session for transaction.
			endStatefulSession();
		}
		
		// Notify connection event listeners of local transaction rollback.
		Collection<ConnectionEventListener> copy = null;
		synchronized (listeners) {
			if (listeners != null && listeners.size() > 0)
				copy = new ArrayList<ConnectionEventListener>(listeners);
		}
		if (copy != null) {
			// JCA 1.5 Specification: 7.7.2.1
			ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.LOCAL_TRANSACTION_ROLLEDBACK);
			for (ConnectionEventListener cel : copy) {
				cel.localTransactionRolledback(event);
			}
		}
		

	}
	
	/**
	 * Called by {@link ConnectionImpl#getDestination()}
	 * 
	 * @return The underlying destination associated with this managed connection.
	 */
	public JCoDestination getDestination() {
		return destination;
	}
	
	/**
	 * Called by {@link ConnectionImpl#begin()}
	 * 
	 * @throws ResourceException If connection is destroyed.
	 */
	public synchronized void beginStatefulSession() throws ResourceException {
		checkState();
		JCoContext.begin(destination);
	}

	/**
	 * Called by {@link ConnectionImpl#end()}
	 * 
	 * @throws ResourceException If connection is destroyed or has outstanding transaction or if error occurs in underlying JCo 
	 * runtime. 
	 */
	public synchronized void endStatefulSession() throws ResourceException {
		checkState();
		
		if (inTransaction)
			throw ExceptionBundle.EXCEPTIONS.connectionHasOutstandingTransaction();
		
		internalEndStatefulSession();
	}
	
	/**
	 * Internal logic to end stateful session.
	 * <p>NB: logic refactored into internal method in order to call without state and transaction checks from {@link #cleanup() }.
	 * @throws ResourceException
	 */
	protected void internalEndStatefulSession() throws ResourceException {
		if (!JCoContext.isStateful(destination))
			return;
		
		try {
			JCoContext.end(destination);
		} catch (JCoException e) {
			throw new ResourceException(e);
		}
	}
	
	/**
	 * Called by {@link ConnectionImpl#isStateful()}
	 * 
	 * @return <code>true</code> if connection is in a stateful state; <code>false</code> otherwise.
	 */
	public boolean isStateful() {
		return JCoContext.isStateful(destination);
	}
	
	/**
	 * Called by {@link ConnectionImpl} and {@link ManagedConnectionImpl} when constructing connection handle.
	 * @return
	 */
	public ConnectionRequestInfoImpl getProperties() {
		return managedConnectionFactory.getResourceAdapter().getDestinationDataProvider().getDestinationProperties(destinationName);
	}
	
	/**
	 * Called by {@link ConnectionImpl#ping()} 
	 * 
	 * @throws ResourceException if underlying JCo runtime throws exception when pinging.
	 */
	public void ping() throws ResourceException {
		try {
			destination.ping();
		} catch (JCoException e) {
			throw ExceptionBundle.EXCEPTIONS.pingFailed(e);
		}
	}

	/**
	 * Called by {@link ConnectionImpl} when constructing itself.
	 * 
	 * @return the managed connection factory associated with this managed connection. 
	 */
	public ManagedConnectionFactory getManagedConnectionFactory() {
		return managedConnectionFactory;
	}

	/**
	 * Called by {@link ConnectionImpl} when associating with this managed connection.
	 * 
	 * @param handle - The handle to be associated with managed connection.
	 */
	public void associateHandle(Connection handle) {
		synchronized (handles) {
			handles.add(handle);
		}
	}

	/**
	 * Called by {@link ManagedConnectionImpl} when closing handle and by {@link ConnectionImpl} when dissociating from managed connection.
	 * 
	 * @param handle - The connection handle to be dissociated from managed connection.
	 */
	public void dissociateHandle(Connection handle) {
		synchronized (handles) {
			handles.remove(handle);
		}
	}

	/**
	 * Close application level handle handle to this connection
	 * 
	 * @param handle -
	 *            The application handle
	 */
	public void closeHandle(Connection handle) {

		dissociateHandle(handle);

		Collection<ConnectionEventListener> copy = null;
		synchronized (listeners) {
			if (listeners != null && listeners.size() > 0)
				copy = new ArrayList<ConnectionEventListener>(listeners);
		}

		if (copy != null) {
			ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.CONNECTION_CLOSED);
			event.setConnectionHandle(handle);
			for (ConnectionEventListener cel : copy) {
				cel.connectionClosed(event);
			}
		}

	}

	/**
	 * Internal helper method used by public methods to check the state of this connection before performing an operation on it. This
	 * method prevents operations from being performed on a connection in a <code>DESTROYED</code> state.
	 * 
	 * @throws ResourceException if connection is in a <code>DESTROYED</code> state.
	 */
	private void checkState() throws ResourceException {
		if (state == State.DESTROYED) {
			throw ExceptionBundle.EXCEPTIONS.connectionIsDestroyed();
		}
	}

}