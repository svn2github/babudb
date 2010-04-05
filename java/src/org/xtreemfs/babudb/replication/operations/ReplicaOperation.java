/*
 * Copyright (c) 2009-2010, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Felix Langner, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
 */
package org.xtreemfs.babudb.replication.operations;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import org.xtreemfs.babudb.interfaces.LogEntries;
import org.xtreemfs.babudb.interfaces.ReplicationInterface.replicaRequest;
import org.xtreemfs.babudb.interfaces.ReplicationInterface.replicaResponse;
import org.xtreemfs.babudb.log.DiskLogIterator;
import org.xtreemfs.babudb.log.LogEntry;
import org.xtreemfs.babudb.lsmdb.CheckpointerImpl;
import org.xtreemfs.babudb.lsmdb.LSN;
import org.xtreemfs.babudb.replication.Request;
import org.xtreemfs.babudb.replication.RequestDispatcher;
import org.xtreemfs.foundation.logging.Logging;

/**
 * {@link Operation} to request {@link LogEntry}s from the master.
 * This operation tries to retrieve the logEntries from the log by iterating
 * over them and returning the requested ones.
 * 
 * @since 05/03/2009
 * @author flangner
 */

public class ReplicaOperation extends Operation {

    private final static int MAX_LOGENTRIES_PER_REQUEST = 500;
    
    private final int procId;
    
    private final RequestDispatcher dispatcher;
    
    private final Checksum checksum = new CRC32();
    
    public ReplicaOperation(RequestDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        procId = new replicaRequest().getTag();
    }

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.operations.Operation#getProcedureId()
     */
    @Override
    public int getProcedureId() {
        return procId;
    }

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.operations.Operation#parseRPCMessage(org.xtreemfs.babudb.replication.Request)
     */
    @Override
    public yidl.runtime.Object parseRPCMessage(Request rq) {
        replicaRequest rpcrq = new replicaRequest();
        rq.deserializeMessage(rpcrq);  
        return null;
    }

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.operations.Operation#startInternalEvent(java.lang.Object[])
     */
    @Override
    public void startInternalEvent(Object[] args) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.operations.Operation#startRequest(org.xtreemfs.babudb.replication.Request)
     */
    @Override
    public void startRequest(Request rq) {
        replicaRequest req = (replicaRequest) rq.getRequestMessage();
        
        LSN start = new LSN(req.getRange().getStart());
        LSN end = new LSN(req.getRange().getEnd());
       
        LogEntries result = new LogEntries();
        
        boolean fakeStart = (start.getSequenceNo() == 0L);
        if (fakeStart) start = new LSN(start.getViewId(), 1L);
        boolean deadEnd = (end.getSequenceNo() == 0L);
        
        Logging.logMessage(Logging.LEVEL_INFO, this, "REQUEST received " +
                "(start: %s, end: %s) from %s", start.toString(), end.toString(),
                rq.getRPCRequest().getClientIdentity());
        
        assert (start.compareTo(end) < 0 || 
               (fakeStart && start.compareTo(end) == 0)) : 
            "Always request at least one LogEntry!";
        
        CheckpointerImpl chkPntr = (CheckpointerImpl) dispatcher.dbs.getCheckpointer();
        LogEntry le = null;
        DiskLogIterator it = null;
        
        synchronized (chkPntr.getCheckpointerLock()) {
            try {   
                chkPntr.waitForCheckpoint();
                
                // get the logFiles for the requested logEntries
                File f = new File(dispatcher.getConfig().getDbLogDir());
                File[] logFiles = f.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return name.endsWith(".dbl");
                    }
                });
                
                it = new DiskLogIterator(logFiles, start);
                
                // discard the first entry, because it was the last inserted
                // entry of the requesting server
                if (!fakeStart) it.next().free();
                
                int counter = 0;
                do {
                    if (!it.hasNext()) {
                        if (result.size() > 0) {
                            break;
                        } else {
                            rq.sendReplicationException(ErrNo.LOG_REMOVED,
                            "LogEntry unavailable.");
                            return;
                        }
                    }
                    le = it.next();
                    if (deadEnd && le.getLSN().compareTo(end) > 0) break;
                    
                    assert (le.getPayload().array().length > 0) : 
                        "Empty log-entries are not allowed!";
                    
                    result.add(new org.xtreemfs.babudb.interfaces.LogEntry(
                            le.serialize(checksum)));
                    checksum.reset();
                    le.free();
                    counter++;
                } while (le.getLSN().compareTo(end) < 0 && 
                        counter < MAX_LOGENTRIES_PER_REQUEST);
                
                // send the response, if the requested log entries are found
                rq.sendSuccess(new replicaResponse(result));
            } catch (Exception e) {
                Logging.logError(Logging.LEVEL_INFO, this, e);
                rq.sendReplicationException(ErrNo.BUSY,
                        "Request not finished: "+e.getMessage(), e);
            } finally {
                checksum.reset();
                if (le != null) le.free();
                if (it != null) {
                    try {
                        it.destroy();
                    } catch (IOException e) { /* ignored */ }
                }
            }
        }
    }
}