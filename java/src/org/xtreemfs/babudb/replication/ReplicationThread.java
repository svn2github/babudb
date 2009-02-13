/*
 * Copyright (c) 2008, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Felix Langner, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
*/
package org.xtreemfs.babudb.replication;

import java.io.File;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetSocketAddress;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import org.xtreemfs.babudb.BabuDBException;
import org.xtreemfs.babudb.BabuDBException.ErrorCode;
import org.xtreemfs.babudb.log.DiskLogFile;
import org.xtreemfs.babudb.log.LogEntry;
import org.xtreemfs.babudb.log.LogEntryException;
import org.xtreemfs.babudb.lsmdb.LSMDBRequest;
import org.xtreemfs.babudb.lsmdb.LSMDBWorker;
import org.xtreemfs.babudb.lsmdb.LSN;
import org.xtreemfs.babudb.replication.Missing.STATUS;
import org.xtreemfs.babudb.replication.Replication.SYNC_MODUS;
import org.xtreemfs.common.buffer.ReusableBuffer;
import org.xtreemfs.common.logging.Logging;
import org.xtreemfs.foundation.LifeCycleListener;
import org.xtreemfs.foundation.LifeCycleThread;
import org.xtreemfs.foundation.json.JSONParser;
import org.xtreemfs.foundation.pinky.HTTPUtils;
import org.xtreemfs.foundation.pinky.PinkyRequest;
import org.xtreemfs.foundation.pinky.PipelinedPinky;
import org.xtreemfs.foundation.pinky.SSLOptions;
import org.xtreemfs.foundation.pinky.HTTPUtils.DATA_TYPE;
import org.xtreemfs.foundation.speedy.MultiSpeedy;
import org.xtreemfs.foundation.speedy.SpeedyRequest;

/**
 * <p>Master-Slave-replication</p>
 * 
 * <p>The Stage for sending DB-changes from the Master to the Servers, and
 * receiving such changes from the Master.</p>
 * 
 * <p>Thread-safe.</p>
 * 
 * <p>Handles a {@link PipelinedPinky}, and a {@link MultiSpeedy} for bidirectional
 * communication with slaves, if the DB is configured as master, or with the master,
 * if it is configured as slave.</p>
 * 
 * @author flangner
 *
 */
/* visibility has to be package - just turn public for testing */
class ReplicationThread extends LifeCycleThread implements LifeCycleListener,UncaughtExceptionHandler{
    
    /**
     * <p>Exception occurs while the replication process.</p>
     * 
     * @author flangner
     *
     */
    class ReplicationException extends Exception {
        /***/
        private static final long serialVersionUID = -3588307362070344055L;

        /**
         * <p>The reason for this exception is here: <code>msg</code>.</p>
         * 
         * @param msg
         */
        ReplicationException(String msg) {
            super(msg);
        }
    }
    
    /**
     * <p>Milliseconds idle-time, if the pending and the missing queues are empty.</p>
     */
    final static int TIMEOUT_GRANULARITY = 250;

    /**
     * <p>Requests that are waiting to be done.</p>
     */
    Queue<Request> pending;
    
    /**
     * <p>LSN's of missing {@link LogEntry}s.</p>
     */
    private Queue<Missing<LSN>> missing;
    
    /**
     * <p>Details for missing file-chunks. File Name + [chunk-beginL,chunk-endL].</p>
     */
    private Queue<Missing<Chunk>> missingChunks;
    
    /**
     * <p>Table of slaves with their latest acknowledged {@link LSN}s.</p>
     */
    private SlavesStatus slavesStatus = null;
    
    /**
     * <p>Pinky - Thread.</p>
     */
    private PipelinedPinky pinky = null;
    
    /**
     * <p> {@link MultiSpeedy} - Thread.</p>
     */
    private MultiSpeedy speedy = null;
    
    /**
     * <p>Holds all variable configuration parameters and is the approach for the complete replication.</p>
     */
    private Replication frontEnd = null;
    
    /**
     * <p>Flag which implies, that this {@link LifeCycleThread} is running.</p>
     */
    private boolean running = false;

    /**
     * <p>Flag which implies the Thread to halt and notify the waiting application.</p>
     */
    AtomicBoolean halt = new AtomicBoolean(false);
    
    /**
     * Lazy holder for the diskLogFile.
     */
    private DiskLogFile diskLogFile = null;
    
    /**
     * If a request could not be processed it will be noticed to wait for newer entries for example.
     * With that polling on the same Request is avoided. 
     */
    private Request firstWaiting = null;
    
    /**
     * <p>Default constructor. Synchronous startup for pinky and speedy component.</p>
     * 
     * @param frontEnd 
     * @param port 
     */
    ReplicationThread(Replication frontEnd, int port, SSLOptions sslOptions) throws ReplicationException{
        super((frontEnd.isMaster()) ? "Master" : "Slave");
        this.frontEnd = frontEnd;
        pending = new PriorityBlockingQueue<Request>();
        missing = (!frontEnd.isMaster()) ? new PriorityBlockingQueue<Missing<LSN>>() : null;
        missingChunks = (!frontEnd.isMaster()) ? new PriorityBlockingQueue<Missing<Chunk>>() : null;
        slavesStatus = (frontEnd.isMaster()) ? new SlavesStatus(frontEnd.slaves) : null;

        try{
           // setup pinky
            pinky = new PipelinedPinky(port,null,frontEnd,sslOptions);
            pinky.setLifeCycleListener(this);
            pinky.setUncaughtExceptionHandler(this);
            
           // setup speedy
            speedy = new MultiSpeedy(sslOptions);
            speedy.registerSingleListener(frontEnd);
            
            speedy.setLifeCycleListener(this);
            speedy.setUncaughtExceptionHandler(this);
        }catch (IOException io){
            String msg = "Could not initialize the Replication because: "+io.getMessage();
            Logging.logMessage(Logging.LEVEL_ERROR, this, msg);
            throw new ReplicationException(msg);
        }

    }
    
/*
 * LifeCycleThread    
 */
    
    /*
     * (non-Javadoc)
     * @see java.lang.Thread#start()
     */
    @Override
    public synchronized void start() {
        try {
           // start pinky
            pinky.start();
            pinky.waitForStartup();
            
           // start speedy
            speedy.start();
            speedy.waitForStartup();  
            
           // start the lifeCycle
            running = true;
            super.start();
        }catch (Exception io){
            String msg = "ReplicationThread crashed, because: "+io.getMessage();
            Logging.logMessage(Logging.LEVEL_ERROR, this, msg);
            notifyCrashed(new ReplicationException(msg)); 
        }  
    }
    
    /**
     * <p>Work the requestQueue <code>pending</code>.</p>
     */
    @Override
    public void run() {  
        final Checksum checkSum = new CRC32(); 
   
        super.notifyStarted();
        while (running) {
          //initialising...
            Chunk chunk = null;
            LSMDBRequest context = null;
            InetSocketAddress source = null;
            PinkyRequest orgReq = null;
            ReusableBuffer buffer = null;
            ReusableBuffer data = null;
            LSN lsn = null;
            Map<String, List<Long>> metaDataLSM = null;
            
          // check for missing LSNs and missing file chunks -highest priority- (just for slaves)          
            if (!frontEnd.isMaster()) {
                try{
                   // if there are any missing file chunks waiting for them has to be synchronous!
                    Missing<Chunk> missingChunk = missingChunks.peek();
                    if (missingChunk != null && missingChunk.stat.compareTo(STATUS.PENDING)<0) 
                        if (missingChunks.remove(missingChunk))
                            sendCHUNK(missingChunk);               
                    
                    Missing<LSN> missingLSN = missing.peek();
                    if (missingLSN != null && missingLSN.stat.compareTo(STATUS.PENDING)<0)
                        sendRQ(missingLSN);
                }catch (ReplicationException re){
                    Logging.logMessage(Logging.LEVEL_ERROR, this, "The master seems to be not available for information-retrieval: "+re.getLocalizedMessage());
                }
            }  
          // get a new request                         
            if (pending.peek() == null || pending.peek().equals(getFirstWaiting())) { 
                try {
                    Thread.sleep(TIMEOUT_GRANULARITY);          
                } catch (InterruptedException e) {
                    Logging.logMessage(Logging.LEVEL_DEBUG, this, "Waiting for work was interrupted.");
                }
            }else{
                Request newRequest = pending.poll();
                setFirstWaiting(null);
                
                try{
                    chunk = newRequest.getChunkDetails();
                    source = newRequest.getSource();
                    orgReq = newRequest.getOriginal();
                    context = newRequest.getContext();
                    data = newRequest.getData();
                    lsn = newRequest.getLSN();
                    metaDataLSM = newRequest.getLsmDbMetaData();
                    
                    switch(newRequest.getToken()) {
                    
                  // master's logic:
                    
                     // sends logEntry to the slaves
                    case REPLICA_BROADCAST :   
                        int replicasFailed = 0;
                        synchronized (frontEnd.slaves) {
                            final int slaveCount = frontEnd.slaves.size();
                            newRequest.setACKsExpected(slaveCount);
                            for (InetSocketAddress slave : frontEnd.slaves) {
                                // build the request
                                SpeedyRequest rq = new SpeedyRequest(HTTPUtils.POST_TOKEN,
                                                                     Token.REPLICA.toString(),null,null,
                                                                     data.createViewBuffer(),
                                                                     DATA_TYPE.BINARY);
                                rq.genericAttatchment = newRequest;
                                
                                // send the request
                                try{  
                                    speedy.sendRequest(rq, slave); 
                                } catch (Exception e){
                                    rq.freeBuffer();
                                    replicasFailed++;
                                    Logging.logMessage(Logging.LEVEL_ERROR, this, "REPLICA could not be send to slave: '"+slave.toString()+"', because: "+e.getMessage());
                                }
                            }
                            
                         // shall not hang, if slave was not available
                            if (replicasFailed!=0 && !frontEnd.syncModus.equals(SYNC_MODUS.ASYNC)) {                            
                                for (int i=0;i<replicasFailed;i++)
                                    newRequest.decreaseACKSExpected(frontEnd.n);
                                
                                 // less than n slaves available for NSYNC mode, or one slave not available in SYNC mode
                                if (frontEnd.syncModus.equals(SYNC_MODUS.SYNC) || 
                                   (frontEnd.syncModus.equals(SYNC_MODUS.NSYNC) && 
                                   (slaveCount-replicasFailed)<frontEnd.n))
                                    throw new ReplicationException("The replication was not fully successful. '"+replicasFailed+"' of '"+slaveCount+"' slaves could not be reached.");
                            
                             // ASYNC mode unavailable slaves will be ignored for the response 
                            } else if(frontEnd.syncModus.equals(SYNC_MODUS.ASYNC)){
                                
                                context.getListener().insertFinished(context);
                                newRequest.free();
                            }
                        }
                        break;
    
                     // answers a slaves logEntry request
                    case RQ : 
                        boolean notFound = true;
                        LSN last;
                        LogEntry le = null;
                        String latestFileName;
                      
                       // get the latest logFile
                        try {
                            latestFileName = frontEnd.dbInterface.logger.getLatestLogFileName();
                            diskLogFile = new DiskLogFile(latestFileName);                           
                            last = frontEnd.dbInterface.logger.getLatestLSN();
                        } catch (IOException e) {
                            // make one attempt to retry (logFile could have been switched)
                            try {
                                latestFileName = frontEnd.dbInterface.logger.getLatestLogFileName();
                                diskLogFile = new DiskLogFile(latestFileName);
                                last = frontEnd.dbInterface.logger.getLatestLSN();
                            } catch (IOException io) {
                                throw new ReplicationException("The diskLogFile seems to be damaged. Reason: "+io.getMessage());
                            }
                        }
                        
                       // check the requested LSN for availability
                        if (last.getViewId()!=lsn.getViewId() || last.compareTo(lsn) < 0)
                            throw new ReplicationException("The requested LogEntry is not available, please load the Database. Last on Master: "+last.toString()+" Requested: "+lsn.toString()); 
                        
                       // parse the diskLogFile
                        while (diskLogFile.hasNext()) {
                            try {
                                if (le!=null) le.free();
                                le = diskLogFile.next();
                            } catch (LogEntryException e1) {
                                if (latestFileName.equals(frontEnd.dbInterface.logger.getLatestLogFileName())) {
                                    throw new ReplicationException("The requested LogEntry is not available, please load the Database.");
                                }
                                
                                // make an attempt to retry (logFile could have been switched)
                                try {
                                    latestFileName = frontEnd.dbInterface.logger.getLatestLogFileName();
                                    diskLogFile = new DiskLogFile(latestFileName);
                                    last = frontEnd.dbInterface.logger.getLatestLSN();
                                    
                                    if (last.getViewId()==lsn.getViewId() || last.compareTo(lsn)<0)
                                        throw new ReplicationException("The requested LogEntry is not available, please load the Database. Last on Master: "+last.toString()+" Requested: "+lsn.toString());
                                } catch (IOException io) {                                  
                                    throw new ReplicationException("The diskLogFile seems to be damaged. Reason: "+io.getMessage());
                                }
                            }
                            
                           // we hit the bullseye
                            if (le.getLSN().equals(lsn)){
                                try {
                                    buffer = le.serialize(checkSum);
                                    le.free();
                                } catch (IOException e) {
                                    throw new ReplicationException("The requested LogEntry is damaged. Reason: "+e.getMessage());
                                }
                                checkSum.reset();
                                orgReq.setResponse(HTTPUtils.SC_OKAY, buffer, DATA_TYPE.BINARY); 
                                notFound = false;
                                break;
                            }
                        }
                        
                       // requested LogEntry was not found 
                        if (notFound)
                            throw new ReplicationException("The requested LogEntry is not available anymore, please load the Database. Last on Master: "+last.toString()+" Requested: "+lsn.toString()); 
                        
                        break;
                     
                     // appreciates the ACK of a slave
                    case ACK :                   
                        if (slavesStatus.update(source.getAddress(),lsn))
                            if (frontEnd.dbInterface.dbCheckptr!=null) // otherwise the checkIntervall is 0
                                frontEnd.dbInterface.dbCheckptr.designateRecommendedCheckpoint(slavesStatus.getLatestCommonLSN());
                        break;
                    
                     // answers a LOAD request of a slave
                    case LOAD :                    
                     // Make the LSM-file metaData JSON compatible, for sending it to a slave
                        try {
                            File[] files = LSMDatabaseMOCK.getAllFiles();
                            Map<String,List<Long>> filesDetails = new Hashtable<String, List<Long>>();
                            for (File file : files) {
                                // TODO make a check of the fileName against the received LSN --> newRequest.getLSN();
                                
                                List<Long> parameters = new LinkedList<Long>();
                                parameters.add(file.length());
                                parameters.add(Replication.CHUNK_SIZE);
                                
                                filesDetails.put(file.getName(), parameters);
                            }
                        
                           // send these informations back to the slave    
                            buffer = ReusableBuffer.wrap(JSONParser.writeJSON(filesDetails).getBytes());
                            orgReq.setResponse(HTTPUtils.SC_OKAY, buffer, DATA_TYPE.JSON);
                        } catch (Exception e) {
                            throw new ReplicationException("LOAD_RQ could not be answered: '"+newRequest.toString()+"', because: "+e.getLocalizedMessage());
                        } 
                        break;                                                    
                        
                     // answers a chunk request of a slave
                    case CHUNK :              
                       // get the requested chunk                      
                        buffer = ReusableBuffer.wrap(
                                LSMDatabaseMOCK.getChunk(chunk.getFileName(),
                                chunk.getBegin(),chunk.getEnd()));                        
                        orgReq.setResponse(HTTPUtils.SC_OKAY, buffer, DATA_TYPE.BINARY);
                        break;   
                        
                  // slave's logic:
                        
                     // integrates a replica from the master
                    case REPLICA :            
                        LSN latestLSN = frontEnd.dbInterface.logger.getLatestLSN(); 
                        Missing<LSN> mLSN = new Missing<LSN>(lsn,STATUS.PENDING);
                        if (missing.remove(mLSN)) missing.add(mLSN); 
                        
                        // check the sequence#
                        if (latestLSN.getViewId() == lsn.getViewId()) {
                            // put the logEntry and send ACK
                            if ((latestLSN.getSequenceNo()+1L) == lsn.getSequenceNo()) {
                                writeLogEntry(newRequest);
                                continue;
                            // already in, so send ACK
                            } else if (latestLSN.getSequenceNo() >= lsn.getSequenceNo()) {
                                if (orgReq!=null) orgReq.setResponse(HTTPUtils.SC_OKAY);
                                else sendACK(newRequest);
                            // get the lost sequences and put it back on pending
                            } else {
                                LSN missingLSN = new LSN (latestLSN.getViewId(),latestLSN.getSequenceNo()+1L);
                                do {
                                    mLSN =  new Missing<LSN>(missingLSN,STATUS.OPEN);
                                    if (!missing.contains(mLSN) && !pending.contains(RequestPreProcessor.getProbeREPLICA(missingLSN)))
                                        missing.add(mLSN);
                                    
                                    missingLSN = new LSN (missingLSN.getViewId(),missingLSN.getSequenceNo()+1L);
                                } while (missingLSN.compareTo(lsn)<0);
                                pending.add(newRequest);
                                setFirstWaiting(newRequest);
                                continue;
                            }
                        // get an initial copy from the master    
                        } else if (latestLSN.getViewId() < lsn.getViewId()){
                            sendLOAD(lsn);
                            pending.add(newRequest);
                            setFirstWaiting(newRequest);
                            continue;
                        } else {
                            if (orgReq!=null) orgReq.setResponse(HTTPUtils.SC_OKAY);
                            else sendACK(newRequest);
                            Logging.logMessage(Logging.LEVEL_WARN, this, "The Master seems to be out of order. Strange LSN received: '"+lsn.toString()+"'; latest LSN is: "+latestLSN.toString());
                        }
                        break;
                        
                     // evaluates the load details from the master
                    case LOAD_RP :
                       // make chunks and store them at the missingChunks                       
                       // for each file 
                        for (String fName : metaDataLSM.keySet()) {
                            Long fileSize = metaDataLSM.get(fName).get(0);
                            Long chunkSize = metaDataLSM.get(fName).get(1);
                            
                            assert (fileSize>0L);
                            assert (chunkSize>0L);
                            
                           // calculate chunks and add them to the list 
                            Long[] range = new Long[2];
                            range[0] = 0L;
                            for (long i=chunkSize;i<fileSize;i+=chunkSize) {
                                range[1] = i;
                                chunk = new Chunk(fName,range[0],range[1]);
                                missingChunks.add(new Missing<Chunk>(chunk,STATUS.OPEN));
                                range = new Long[2];
                                range[0] = i;
                            }
                            
                           // put the last chunk
                            if ((range[0]-fileSize)!=0L) {
                                chunk = new Chunk(fName,range[0],fileSize);
                                missingChunks.add(new Missing<Chunk>(chunk,STATUS.OPEN));
                            }
                        }                      
                        break;
                        
                     // saves a chunk sended by the master
                    case CHUNK_RP :                   
                        LSMDatabaseMOCK.writeFileChunk(chunk.getFileName(),data,chunk.getBegin(),chunk.getEnd());
                        break;
                        
                    default : 
                        assert(false) : "Unknown Request will be ignored: "+newRequest.toString();                       
                        throw new ReplicationException("Unknown Request will be ignored: "+newRequest.toString());
                    }
                    
                 // make a notification for a client/application if necessary   
                }catch (ReplicationException re){                    
                   // send a response to the client
                    if (orgReq!=null){
                        assert(!orgReq.responseSet) : "Response of the pinky request is already set! Request: "+newRequest.toString();                        
                        orgReq.setResponse(HTTPUtils.SC_SERVER_ERROR,re.getMessage());
                    } 
                    
                   // send a response to the application
                    if (context!=null){
                        context.getListener().requestFailed(context.getContext(), 
                                new BabuDBException(ErrorCode.REPLICATION_FAILURE, re.getMessage())); 
                    }
                 
                    Logging.logMessage(Logging.LEVEL_ERROR, this, re.getLocalizedMessage());
                }
                
                // send a pinky response
                sendResponse(orgReq);
            }
            
            synchronized (halt){
                while (halt.get() == true){
                    halt.notify();
                    try {
                        halt.wait();
                    } catch (InterruptedException e) {
                        Logging.logMessage(Logging.LEVEL_DEBUG, this, "Waiting for context switch was interrupted.");
                    }
                }
            }           
        }
        
        if (running) notifyCrashed(new Exception("ReplicationThread crashed for an unknown reason!"));       
        notifyStopped();
    }
    
    /**
     * <p>Write the {@link LogEntry} from the given {@link Request} <code>req</code> to the DiskLogger and 
     * insert it into the LSM-tree.</p>
     *
     * @param req
     */
    private void writeLogEntry(Request req){
        try {
            int dbId = req.getContext().getInsertData().getDatabaseId();
            LSMDBWorker worker = frontEnd.dbInterface.getWorker(dbId);
            worker.addRequest(req.getContext());
        } catch (InterruptedException e) {
           // try again - if worker was interrupted
            pending.add(req);
            Logging.logMessage(Logging.LEVEL_ERROR, this, "LogEntry could not be written and will be put back into the pending queue," +
                    "\r\n\t because: "+e.getLocalizedMessage());
        }
    }
      
    /**
     * <p>Sends an {@link Token}.RQ for the given {@link Missing} <code>lsn</code> to the master.<p>
     * 
     * @param lsn
     * @throws ReplicationException - if an error occurs.
     */
    private void sendRQ(Missing<LSN> lsn) throws ReplicationException {
        try {          
            SpeedyRequest sReq = new SpeedyRequest(HTTPUtils.POST_TOKEN,Token.RQ.toString(),null,null,ReusableBuffer.wrap(((LSN) lsn.c).toString().getBytes()),DATA_TYPE.BINARY);
            sReq.genericAttatchment = lsn;
            speedy.sendRequest(sReq, frontEnd.master);
            lsn.stat = STATUS.PENDING; 
        } catch (Exception e) {   
            lsn.stat = STATUS.FAILED;
            throw new ReplicationException("RQ ("+lsn.toString()+") could not be send to master: '"+frontEnd.master.toString()+"', because: "+e.getLocalizedMessage());           
        } 
        
        // reorder the missing lsn
        if (missing.remove(lsn))
            missing.add(lsn);
    } 
    
    /**
     * <p>Sends an {@link Token}.CHUNK_RQ for the given <code>chunk</code> to the master.</p>
     * 
     * @param chunk
     * @throws ReplicationException - if an error occurs.
     */
    private void sendCHUNK(Missing<Chunk> chunk) throws ReplicationException {
        try {           
            SpeedyRequest sReq = new SpeedyRequest(HTTPUtils.POST_TOKEN,
                    Token.CHUNK.toString(),null,null,
                    ReusableBuffer.wrap(JSONParser.writeJSON(((Chunk)chunk.c).toJSON()).getBytes()),DATA_TYPE.JSON);
            sReq.genericAttatchment = chunk;
            speedy.sendRequest(sReq, frontEnd.master);
            chunk.stat = STATUS.PENDING;
        } catch (Exception e) {   
            chunk.stat = STATUS.FAILED;
            throw new ReplicationException("CHUNK ("+chunk.toString()+") could not be send to master: '"+frontEnd.master.toString()+"', because: "+e.getLocalizedMessage());           
        } 
        missingChunks.add(chunk);
    }
    
    /**
     * <p>Sends an {@link Token}.LOAD to the given {@link LSN} <code>lsn</code> to the master.</p>
     * 
     * @param lsn
     * @throws ReplicationException - if an error occurs.
     */
    private void sendLOAD(LSN lsn) throws ReplicationException{
        try {
            SpeedyRequest sReq = new SpeedyRequest(HTTPUtils.POST_TOKEN,Token.LOAD.toString(),null,null,ReusableBuffer.wrap(lsn.toString().getBytes()),DATA_TYPE.BINARY);
            speedy.sendRequest(sReq, frontEnd.master);
        } catch (Exception e) {
            throw new ReplicationException("LOAD till ("+lsn.toString()+") could not be send to master: '"+frontEnd.master.toString()+"', because: "+e.getLocalizedMessage());
        } 
    }
    
    /**
     * <p>Used for shutting down the ReplicationThread, if a essential component crashed.</p>
     */
    private void violentShutdown() {
        running = false;
        if (pinky!=null && pinky.isAlive()) pinky.shutdown();
        if (speedy!=null && speedy.isAlive()) speedy.shutdown();      
    }
  
/*
 * shared functions
 */
      
    /**
     * <p>Sends a synchronous request with JSON data attached to all available slaves.</p>
     *     
     * @param rq
     * @throws ReplicationException
     */
    void sendSynchronousRequest(Request rq) throws ReplicationException{
        try {
            synchronized (frontEnd.slaves) {
                rq.setACKsExpected(frontEnd.slaves.size());
                for (InetSocketAddress slave : frontEnd.slaves){
                    SpeedyRequest sReq = new SpeedyRequest(HTTPUtils.POST_TOKEN,rq.getToken().toString(),null,null,rq.getData(),DATA_TYPE.JSON);
                    sReq.genericAttatchment = rq;
                    speedy.sendRequest(sReq, slave);
                }    
                
                synchronized(rq) {
                    rq.wait();
                }
            }

            if (rq.failed()) throw new Exception("Operation failed!");
        } catch (Exception e) {
            throw new ReplicationException(rq.getToken().toString()+" could not be replicated. Because: "+e.getMessage());   
        }
    }
    
    /**
     * <p>Checks the {@link PinkyRequest} <code>rq</code> against <code>null</code>, before sending a Response.</p>
     * 
     * @param rq
     */
    void sendResponse(PinkyRequest rq) {
        if (rq!=null) {
            if (!rq.responseSet)
                rq.setResponse(HTTPUtils.SC_OKAY); 
            
            pinky.sendResponse(rq);                
        }      
    }
    
    /**
     * <p>Sends an {@link Token}.ACK for the given {@link LSN} <code>lsn</code> to the master.</p>
     * 
     * @param rq - for retrying purpose.
     * @throws ReplicationException - if an error occurs.
     */
    void sendACK(Request rq) throws ReplicationException{
        try {
            SpeedyRequest sReq = new SpeedyRequest(HTTPUtils.POST_TOKEN,Token.ACK.toString(),null,null,ReusableBuffer.wrap(rq.getLSN().toString().getBytes()),DATA_TYPE.BINARY);
            speedy.sendRequest(sReq, frontEnd.master);
        } catch (Exception e) {
            throw new ReplicationException("ACK ("+rq.getLSN().toString()+") could not be send to master: '"+frontEnd.master.toString()+"', because: "+e.getLocalizedMessage());   
        } 
    }
    
    /**
     * <p>Approach to remove missing LSNs if the given rq was written to the BabuDB.</p>
     * 
     * @param rq
     */
    void removeMissing(Request rq){
        missing.remove(new Missing<LSN>(rq.getLSN(),null)); // null is allowed, because the status is not interesting in this case
        setFirstWaiting(rq);
    }
    
    void toMaster(){
        missing = null;
        missingChunks = null;
        if (slavesStatus == null)
            slavesStatus = new SlavesStatus(frontEnd.slaves);
    }
    
    void toSlave(){
        if (missing == null)
            missing = new PriorityBlockingQueue<Missing<LSN>>();
        if (missingChunks == null)
            missingChunks = new PriorityBlockingQueue<Missing<Chunk>>();
        slavesStatus = null;
    }
    
    /**
     * <p>Shuts the {@link ReplicationThread} down gracefully. All connections are closed.</p>
     * @throws Exception 
     */
    void shutdown() throws Exception {
        running = false;
        pinky.shutdown();
        speedy.shutdown();
        pinky.waitForShutdown();
        speedy.waitForShutdown();        
    }

/*
 * LifeCycleListener for Pinky & Speedy
 */
    
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.foundation.LifeCycleListener#crashPerformed()
     */  
    @Override
    public void crashPerformed() {
        String message = "";
        if (frontEnd.isMaster())
            message = "A component of the Master ReplicationThread crashed. The ReplicationThread will be shut down.";
        else
            message = "A component of the Slave ReplicationThread crashed. The ReplicationThread will be shut down.";
        Logging.logMessage(Logging.LEVEL_ERROR, this, message);       
        violentShutdown();
    }


    /*
     * (non-Javadoc)
     * @see org.xtreemfs.foundation.LifeCycleListener#shutdownPerformed()
     */
    @Override
    public void shutdownPerformed() {
        String message = "";
        if (frontEnd.isMaster())
            message = "Master ReplicationThread component stopped.";
        else
            message = "Slave ReplicationThread component stopped.";
        Logging.logMessage(Logging.LEVEL_INFO, this, message);      
    }


    /*
     * (non-Javadoc)
     * @see org.xtreemfs.foundation.LifeCycleListener#startupPerformed()
     */
    @Override
    public void startupPerformed() {
        String message = "";
        if (frontEnd.isMaster())
            message = "Master ReplicationThread component started.";
        else
            message = "Slave ReplicationThread component started.";
        Logging.logMessage(Logging.LEVEL_INFO, this, message);  
    }
    
    /*
     * (non-Javadoc)
     * @see java.lang.Thread.UncaughtExceptionHandler#uncaughtException(java.lang.Thread, java.lang.Throwable)
     */
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        Logging.logMessage(Logging.LEVEL_ERROR, this, "Critical error in thread: "+t.getName()+" because: "+e.getLocalizedMessage());
    }  
    
/*
 * Getter/Setter for the 'first waiting' flag
 */
    
    private synchronized Request getFirstWaiting(){       
        return firstWaiting;
    }
    
    private synchronized void setFirstWaiting(Request rq){
        firstWaiting = rq;
    }
}