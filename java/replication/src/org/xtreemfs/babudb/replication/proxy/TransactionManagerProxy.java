/*
 * Copyright (c) 2010 - 2011, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Felix Langner, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
 */
package org.xtreemfs.babudb.replication.proxy;

import java.net.InetSocketAddress;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import org.xtreemfs.babudb.BabuDBRequestResultImpl;
import org.xtreemfs.babudb.api.database.DatabaseRequestListener;
import org.xtreemfs.babudb.api.dev.transaction.InMemoryProcessing;
import org.xtreemfs.babudb.api.dev.transaction.TransactionInternal;
import org.xtreemfs.babudb.api.dev.transaction.TransactionManagerInternal;
import org.xtreemfs.babudb.api.exception.BabuDBException;
import org.xtreemfs.babudb.api.exception.BabuDBException.ErrorCode;
import org.xtreemfs.babudb.api.transaction.TransactionListener;
import org.xtreemfs.babudb.config.ReplicationConfig;
import org.xtreemfs.babudb.log.DiskLogger;
import org.xtreemfs.babudb.log.LogEntry;
import org.xtreemfs.babudb.log.SyncListener;
import org.xtreemfs.babudb.lsmdb.LSN;
import org.xtreemfs.babudb.replication.LockableService;
import org.xtreemfs.babudb.replication.ReplicationManager;
import org.xtreemfs.babudb.replication.policy.Policy;
import org.xtreemfs.babudb.replication.service.accounting.ReplicateResponse;
import org.xtreemfs.babudb.replication.service.clients.ClientResponseFuture.ClientResponseAvailableListener;
import org.xtreemfs.babudb.replication.transmission.client.ReplicationClientAdapter.ErrorCodeException;
import org.xtreemfs.foundation.buffer.BufferPool;
import org.xtreemfs.foundation.buffer.ReusableBuffer;
import org.xtreemfs.foundation.logging.Logging;

import static org.xtreemfs.babudb.log.LogEntry.*;
import static org.xtreemfs.babudb.replication.transmission.ErrorCode.mapTransmissionError;
import static org.xtreemfs.babudb.api.transaction.Operation.*;
import static org.xtreemfs.babudb.api.dev.transaction.TransactionInternal.containsOperationType;

/**
 * This implementation of {@link TransactionManager} redirects makePersistent
 * requests to the replication master, if it's currently not the local BabuDB.
 * 
 * @author flangner
 * @since 11/04/2010
 */
class TransactionManagerProxy extends TransactionManagerInternal implements LockableService {

    private final ReplicationManager            replMan;
    private final TransactionManagerInternal    localTxnMan;
    private final Policy                        replicationPolicy;
    private final BabuDBProxy                   babuDBProxy;
    private final AtomicInteger                 accessCounter = new AtomicInteger(0);
    private boolean                             locked = true;
    
    public TransactionManagerProxy(ReplicationManager replMan,
            TransactionManagerInternal localTxnMan, Policy replicationPolicy, 
            BabuDBProxy babuDBProxy) {
        
        this.babuDBProxy = babuDBProxy;
        this.replicationPolicy = replicationPolicy;
        this.replMan = replMan;
        this.localTxnMan = localTxnMan;
        
        // copy in memory processing logic from the local persistence manager
        for (Entry<Byte, InMemoryProcessing> e : localTxnMan.getProcessingLogic().entrySet()) {
            registerInMemoryProcessing(e.getKey(), e.getValue());
        }
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.dev.transaction.TransactionManagerInternal#makePersistent(
     *          org.xtreemfs.babudb.api.dev.transaction.TransactionInternal, 
     *          org.xtreemfs.foundation.buffer.ReusableBuffer)
     */
    @Override
    public void makePersistent(TransactionInternal txn, 
            ReusableBuffer serialized, BabuDBRequestResultImpl<Object> future) 
            throws BabuDBException {
        
        assert (serialized != null);
        
        try {
            InetSocketAddress master = getServerToPerformAt(txn.aggregateOperationTypes());
            if (master == null) {
                
                // check if this service has been locked and increment the access counter
                synchronized (accessCounter) {
                    try {
                        while (isLocked()) {
                            accessCounter.wait();
                        }
                    } catch (InterruptedException e) {
                        throw new BabuDBException(ErrorCode.INTERRUPTED, 
                                "Waiting for a lease holder was interrupted.", e);    
                    }
                    accessCounter.incrementAndGet();
                }
                executeLocallyAndReplicate(txn, serialized, future);
            } else {      
                redirectToMaster(serialized, master, future);
            }
        } catch (BabuDBException be) {
            BufferPool.free(serialized);
            throw be;
        }
    }
    
    /**
     * Executes the request locally and tries to replicate it on the other 
     * participating BabuDB instances.
     * 
     * @param <T>
     * @param txn
     * @param payload
     * @param future
     * 
     * @return the requests result future.
     * @throws BabuDBException
     */
    private void executeLocallyAndReplicate(TransactionInternal txn, final ReusableBuffer payload, 
            final BabuDBRequestResultImpl<Object> future) throws BabuDBException {
        
        final BabuDBRequestResultImpl<Object> localFuture = 
            new BabuDBRequestResultImpl<Object>(babuDBProxy.getResponseManager());
        localTxnMan.makePersistent(txn, payload.createViewBuffer(), localFuture);
        localFuture.registerListener(new DatabaseRequestListener<Object>() {
        
            @Override
            public void finished(Object result, Object context) {
                
                // request has finished. decrement the access counter
                synchronized (accessCounter) {
                    if (accessCounter.decrementAndGet() == 0) {
                        accessCounter.notify();
                    }
                }
                
                LSN assignedByDiskLogger = localFuture.getAssignedLSN();
                LogEntry le = new LogEntry(payload, new ListenerWrapper(future, result), 
                                           PAYLOAD_TYPE_TRANSACTION);
                le.assignId(assignedByDiskLogger.getViewId(), assignedByDiskLogger.getSequenceNo());
                
                ReplicateResponse rp = replMan.replicate(le);
                if (!rp.hasFailed()) {
                    replMan.subscribeListener(rp);
                }
                
                le.free();
            }
            
            @Override
            public void failed(BabuDBException error, Object context) {
                
                // request has finished. decrement the access counter
                synchronized (accessCounter) {
                    if (accessCounter.decrementAndGet() == 0) {
                        accessCounter.notify();
                    }
                }
                
                future.failed(error);
            }
        });
    }
    
    /**
     * Executes the request remotely at the BabuDB instance with master 
     * privilege.
     * 
     * @param <T>
     * @param load
     * @param master
     * @param future
     * @return the request response future.
     */
    private void redirectToMaster(ReusableBuffer load, InetSocketAddress master, 
            BabuDBRequestResultImpl<Object> future) {
        
        babuDBProxy.getClient().makePersistent(master, load).registerListener(
                new ListenerWrapper(future));
    }
    
    private boolean isLocked() {
        synchronized (accessCounter) {
            try {
                if (locked) {
                    accessCounter.wait(ReplicationConfig.DELAY_TO_WAIT_FOR_LEASE_MS);
                }
            } catch (InterruptedException e) {
                /* I don't care */
            }
            return locked;
        }
    }
    
    /**
     * @param aggregatedType - of the request.
     * 
     * @return the host to perform the request at, or null, if it is permitted to perform the 
     *         request locally.
     * @throws BabuDBException if replication is currently not available.
     */
    private InetSocketAddress getServerToPerformAt (byte aggregatedType) throws BabuDBException {
        
        InetSocketAddress master;
        try {
            master = replMan.getMaster();
        } catch (InterruptedException e) {
            throw new BabuDBException(ErrorCode.INTERRUPTED, 
                    "Waiting for a lease holder was interrupted.", e);
        }
               
        if ((replMan.isItMe(master)) ||
                
            (containsOperationType(aggregatedType, TYPE_GROUP_INSERT) && 
                    !replicationPolicy.insertIsMasterRestricted()) ||
            
            ((containsOperationType(aggregatedType, TYPE_CREATE_SNAP) ||
              containsOperationType(aggregatedType, TYPE_DELETE_SNAP)) && 
                    !replicationPolicy.snapshotManipultationIsMasterRestricted()) ||
                    
            ((containsOperationType(aggregatedType, TYPE_CREATE_DB) ||
              containsOperationType(aggregatedType, TYPE_COPY_DB) ||
              containsOperationType(aggregatedType, TYPE_DELETE_DB)) && 
                    !replicationPolicy.dbModificationIsMasterRestricted())) {
            
            return null;
        }
        
        return master;
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.PersistenceManager#lockService()
     */
    @Override
    public void lockService() throws InterruptedException {
        localTxnMan.lockService();
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.PersistenceManager#unlockService()
     */
    @Override
    public void unlockService() { 
        localTxnMan.unlockService();
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.PersistenceManager#setLogger(org.xtreemfs.babudb.log.DiskLogger)
     */
    @Override
    public void setLogger(DiskLogger logger) {
        localTxnMan.setLogger(logger);
    }
    

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.PersistenceManager#getLatestOnDiskLSN()
     */
    @Override
    public LSN getLatestOnDiskLSN() {
        return localTxnMan.getLatestOnDiskLSN();
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.PersistenceManager#init(org.xtreemfs.babudb.lsmdb.LSN)
     */
    @Override
    public void init(LSN initial) {
        localTxnMan.init(initial);
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.proxy.LockableService#lock()
     */
    @Override
    public void lock() throws InterruptedException {       
        synchronized (accessCounter) {
            locked = true;
            while (locked && accessCounter.get() > 0) {
                accessCounter.wait();
            }
        }
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.proxy.LockableService#unlock()
     */
    @Override
    public void unlock() {
        synchronized (accessCounter) {
            locked = false;
            accessCounter.notifyAll();
        }
    }

    /**
     * Wrapper class for encapsulating and updating the 
     * {@link DatabaseRequestListener} connected to the request results.
     * 
     * @author flangner
     * @since 19.01.2011
     */
    private final static class ListenerWrapper implements ClientResponseAvailableListener<Object>, 
            SyncListener {
        
        private final BabuDBRequestResultImpl<Object> requestFuture;
        private final Object                          result;
        
        public ListenerWrapper(BabuDBRequestResultImpl<Object> requestFuture) {
            this.requestFuture = requestFuture;
            this.result = null;
        }
        
        public ListenerWrapper(BabuDBRequestResultImpl<Object> requestFuture, Object result) {
            this.requestFuture = requestFuture;
            this.result = result;
        }
        
        /* (non-Javadoc)
         * @see org.xtreemfs.babudb.replication.service.clients.ClientResponseFuture.
         *              ClientResponseAvailableListener#responseAvailable(java.lang.Object)
         */
        @Override
        public void responseAvailable(Object r) {
            requestFuture.finished(r);
        }

        /* (non-Javadoc)
         * @see org.xtreemfs.babudb.replication.service.clients.ClientResponseFuture.
         *              ClientResponseAvailableListener#requestFailed(java.lang.Exception)
         */
        @Override
        public void requestFailed(Exception e) {
            Logging.logError(Logging.LEVEL_WARN, this, e);
            
            if (e instanceof ErrorCodeException) {
                requestFuture.failed(new BabuDBException(mapTransmissionError(
                        ((ErrorCodeException) e).getCode()),e.getMessage()));
            } else {
                requestFuture.failed(new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                        e.getMessage()));
            }
        }

        /* (non-Javadoc)
         * @see org.xtreemfs.babudb.log.SyncListener#synced(org.xtreemfs.babudb.log.LogEntry)
         */
        @Override
        public void synced(LSN lsn) {
            requestFuture.finished(result, lsn);
        }

        /* (non-Javadoc)
         * @see org.xtreemfs.babudb.log.SyncListener#failed(org.xtreemfs.babudb.log.LogEntry, 
         *              java.lang.Exception)
         */
        @Override
        public void failed(Exception ex) {
            Logging.logError(Logging.LEVEL_WARN, this, ex);
            
            requestFuture.failed(
                    new BabuDBException(ErrorCode.REPLICATION_FAILURE, ex.getMessage()));
        }
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.dev.transaction.TransactionManagerInternal#replayTransaction(org.xtreemfs.babudb.api.dev.transaction.TransactionInternal)
     */
    @Override
    public void replayTransaction(TransactionInternal txn) throws BabuDBException {
        this.localTxnMan.replayTransaction(txn);
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.dev.transaction.TransactionManagerInternal#addTransactionListener(org.xtreemfs.babudb.api.transaction.TransactionListener)
     */
    @Override
    public void addTransactionListener(TransactionListener listener) {
        this.localTxnMan.addTransactionListener(listener);
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.dev.transaction.TransactionManagerInternal#removeTransactionListener(org.xtreemfs.babudb.api.transaction.TransactionListener)
     */
    @Override
    public void removeTransactionListener(TransactionListener listener) {
        this.localTxnMan.removeTransactionListener(listener);
    }
}
