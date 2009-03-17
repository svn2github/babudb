/*
 * Copyright (c) 2009, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Felix Langner, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
 */
package org.xtreemfs.babudb.replication;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.xtreemfs.babudb.log.LogEntry;
import org.xtreemfs.babudb.lsmdb.LSMDBRequest;
import org.xtreemfs.babudb.lsmdb.LSN;
import org.xtreemfs.include.common.buffer.BufferPool;
import org.xtreemfs.include.common.buffer.ReusableBuffer;
import org.xtreemfs.include.common.logging.Logging;
import org.xtreemfs.include.foundation.pinky.PinkyRequest;

/**
 * <p><b>To get instances of that use the {@link RequestPreProcessor}!</b></p>
 * 
 * <p>Wrapper for the different requests the {@link ReplicationThread} has to
 * handle.</p>
 * 
 * @author flangner
 *
 */
class RequestImpl implements Request {
    /** the identifier for this request */
    Token                       token                   = null;

    /** the source, where the request comes from */
    InetSocketAddress           source                  = null;
    
    /** the identification of a {@link LogEntry} */
    LSN                         lsn                     = null;
  
    /** the identifier for a {@link Chunk} */
    Chunk                       chunkDetails            = null;
    
    /** the {@link LogEntry} received as answer of an request */
    LogEntry                    logEntry                = null;
    
    /** {@link Chunk} data or a serialized {@link LogEntry} to send */
    ReusableBuffer              data                    = null;

    /** for response issues and to be checked into the DB */
    LSMDBRequest                context                 = null;
    
    /** for response issues too */
    PinkyRequest                original                = null;
    
    /** for requesting the initial load by pieces */
    Map<String, List<Long>>     lsmDbMetaData           = null;
            
    /** status of an broadCast request - the expected ACKs */
    private final AtomicInteger maxReceivableACKs       = new AtomicInteger(0);
    private final AtomicInteger minExpectableACKs       = new AtomicInteger(0);
    
    RequestImpl(Token t){
        token = t;
    }
    
    RequestImpl(Token t,InetSocketAddress s){
        token = t;
        source = s;
    }
   
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#free()
     */
    public void free() {
        if (data!=null){
            BufferPool.free(data);
            data = null;
        }
        
        if (logEntry!=null){
            logEntry.free();
            logEntry = null;
        }
    }
 
/*
 * getter/setter    
 */      
    
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#getToken()
     */
    public Token getToken() {
        return token;
    }

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#getLSN()
     */
    public LSN getLSN() {
        return lsn;
    }

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#getSource()
     */
    public InetSocketAddress getSource() {
        return source;
    }
      
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#getChunkDetails()
     */
    public Chunk getChunkDetails() {
        return chunkDetails;
    }
   
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#getData()
     */
    public ReusableBuffer getData() {
        return data;
    }

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#getContext()
     */
    public LSMDBRequest getContext() {
        return context;
    }
    
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#getOriginal()
     */
    public PinkyRequest getOriginal() {
        return original;
    }   

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#getLsmDbMetaInformation()
     */
    public Map<String, List<Long>> getLsmDbMetaData() {
        return lsmDbMetaData;
    }
    
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#getLogEntry()
     */
    public LogEntry getLogEntry(){
        return logEntry;
    }
    
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#setMaxReceivableACKs(int)
     */
    public void setMaxReceivableACKs(int count) {
        assert (count > 0) : "There has to be at least one receiver of the request.";
        maxReceivableACKs.set(count);
    }

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#decreaseMaxReceivableACKs(int)
     */
    public boolean decreaseMaxReceivableACKs(int count) {
        assert (count > 0) : "The amount to decrease has to be positive not-0 integer.";
        
        int newMax = 0;
        for (int i=0;i<count;i++)
            newMax = maxReceivableACKs.decrementAndGet();
        
        assert (newMax >= 0) : "There cannot be less than 0 expected receivable ACKs left. Especially not: "+newMax;
        
        return newMax >= minExpectableACKs.get();
    }
    
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#setMinExpectableACKs(int)
     */
    public void setMinExpectableACKs(int count) {
        minExpectableACKs.set(count);
    }

    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#decreaseMinExpectableACKs()
     */
    public boolean decreaseMinExpectableACKs() {
        int remaining = minExpectableACKs.decrementAndGet();
        maxReceivableACKs.decrementAndGet();        
        return remaining == 0;
    }
    
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Request#hasFailed()
     */
    public boolean hasFailed(){
        return minExpectableACKs.get()!=0;
    }
    
    /*
     * (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        Request rq = (Request) o;
        
        if (token.equals(rq.getToken())){
            switch (rq.getToken()){
            case ACK:
                return (source.equals(rq.getSource())
                        && lsn.equals(rq.getLSN()));
                
            case CHUNK:
                return (source.equals(rq.getSource())
                        && chunkDetails.equals(rq.getChunkDetails()));
                
            case CHUNK_RP:
                return (source.equals(rq.getSource())
                        && chunkDetails.equals(rq.getChunkDetails()));
                
            case LOAD:
                return source.equals(rq.getSource());
                
            case LOAD_RP:
                return (source.equals(rq.getSource())
                        && lsmDbMetaData.equals(rq.getLsmDbMetaData()));
                
            case LOAD_RQ: return true;
                
            case REPLICA:
                return (lsn.equals(rq.getLSN()));
                
            case REPLICA_BROADCAST:
                return lsn.equals(rq.getLSN());
                
            case RQ:
                return (source.equals(rq.getSource())
                        && lsn.equals(rq.getLSN()));
                
            case STATE: 
                return source.equals(rq.getSource());
                
            default: return false;                
            }
        }return false;
    }
    
    /*
     * (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        String string = new String();
        if (token!=null)            string+="Token: '"+token.toString()+"',";
        if (source!=null)           string+="Source: '"+source.toString()+"',";
        if (lsn!=null)              string+="LSN: '"+lsn.toString()+"',";  
        if (logEntry!=null)         string+="LogEntry: '"+logEntry.toString()+"',"; 
        if (chunkDetails!=null)     string+="ChunkDetails: '"+chunkDetails.toString()+"',";
        if (Logging.tracingEnabled()) {
            if (data!=null)             string+="data : "+data.array()+"',";
            if (lsmDbMetaData!=null)    string+="LSM DB metaData: "+lsmDbMetaData.toString()+"',";
            if (context!=null)          string+="context: "+context.toString()+"',";      
            if (original!=null)         string+="the original PinkyRequest is available : "+original.toString()+"',";       
        } else 
            string += "\nEnable tracing for more informations.";

        string+="Object's id: "+super.toString();
        return string;
    }
}
