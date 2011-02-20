/*
 * Copyright (c) 2010 - 2011, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Felix Langner, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
 */
package org.xtreemfs.babudb.replication.control;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;

import org.xtreemfs.babudb.config.ReplicationConfig;
import org.xtreemfs.babudb.log.LogEntry;
import org.xtreemfs.babudb.log.SyncListener;
import org.xtreemfs.babudb.replication.FleaseMessageReceiver;
import org.xtreemfs.babudb.replication.LockableService;
import org.xtreemfs.babudb.replication.TopLayer;
import org.xtreemfs.babudb.replication.control.TimeDriftDetector.TimeDriftListener;
import org.xtreemfs.babudb.replication.service.ServiceToControlInterface;
import org.xtreemfs.foundation.LifeCycleListener;
import org.xtreemfs.foundation.buffer.ASCIIString;
import org.xtreemfs.foundation.flease.Flease;
import org.xtreemfs.foundation.flease.FleaseStage;
import org.xtreemfs.foundation.flease.FleaseViewChangeListenerInterface;
import org.xtreemfs.foundation.flease.MasterEpochHandlerInterface;
import org.xtreemfs.foundation.flease.comm.FleaseMessage;
import org.xtreemfs.foundation.logging.Logging;

/**
 * Contains the control logic for steering the replication process.
 * 
 * @author flangner
 * @since 02/24/2010
 */
public class ControlLayer extends TopLayer implements TimeDriftListener, FleaseMessageReceiver, 
        FleaseEventListener {
    
    /** designation of the flease-cell */
    private final static ASCIIString        REPLICATION_CELL = new ASCIIString("replication");
    
    /** always access {@link Flease} from here */
    private final FleaseStage               fleaseStage;
    
    private final List<InetSocketAddress>   fleaseParticipants;
    
    /**
     * component to ensure {@link Flease}'s requirement of loosely synchronized
     * clocks
     */
    private final TimeDriftDetector         timeDriftDetector;
    
    /** interface to the underlying layer */
    private final ServiceToControlInterface serviceInterface;
    
    /** the local address used for the net-communication */
    private final InetAddress               thisAddress;
    
    /** listener and storage for the up-to-date lease informations */
    private final FleaseHolder              leaseHolder;
    
    /** services that have to be locked during failover */
    private LockableService                 userInterface;
    private LockableService                 replicationInterface;
    
    public ControlLayer(ServiceToControlInterface serviceLayer, ReplicationConfig config) 
            throws IOException {
        
        // ----------------------------------
        // initialize the time drift detector
        // ----------------------------------
        timeDriftDetector = new TimeDriftDetector(this, 
                serviceLayer.getParticipantOverview().getConditionClients(), 
                config.getLocalTimeRenew());
        
        // ----------------------------------
        // initialize the replication 
        // controller
        // ---------------------------------- 
        thisAddress = config.getAddress();
        serviceInterface = serviceLayer;
        leaseHolder = new FleaseHolder(REPLICATION_CELL, this);
        
        // ----------------------------------
        // initialize Flease
        // ----------------------------------
        File bDir = new File(config.getBabuDBConfig().getBaseDir());
        if (!bDir.exists()) bDir.mkdirs();

        fleaseParticipants = new LinkedList<InetSocketAddress>(config.getParticipants());
        fleaseStage = new FleaseStage(config.getFleaseConfig(), 
                config.getBabuDBConfig().getBaseDir(), 
                new FleaseMessageSender(serviceLayer.getParticipantOverview()), false, 
                new FleaseViewChangeListenerInterface() {
                    /* does not influence the replication */
                    @Override
                    public void viewIdChangeEvent(ASCIIString cellId, int viewId) { }
                    
                }, leaseHolder, new MasterEpochHandlerInterface() {
                    /* does not influence the replication */
                    @Override
                    public void storeMasterEpoch(FleaseMessage request, Continuation callback) {                        
                        callback.processingFinished();
                    }
                    
                    @Override
                    public void sendMasterEpoch(FleaseMessage response, Continuation callback) {
                        callback.processingFinished();
                    }
                });
    }  
        
/*
 * overridden methods
 */
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.TopLayer#lockAll()
     */
    @Override
    public void lockAll() throws InterruptedException {
        userInterface.lock();
        replicationInterface.lock();
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.TopLayer#unlockUser()
     */
    @Override
    public void unlockUser() {
        userInterface.unlock();
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.TopLayer#unlockReplication()
     */
    @Override
    public void unlockReplication() {
        replicationInterface.unlock();
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.control.ControlToBabuDBInterface#getLeaseHolder()
     */
    @Override
    public InetSocketAddress getLeaseHolder() {
        return leaseHolder.getLeaseHolderAddress();
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.control.ControlToBabuDBInterface#isItMe(java.net.InetSocketAddress)
     */
    @Override
    public boolean isItMe(InetSocketAddress address) {
        return thisAddress.equals(address);
    }
    
    /*
     * (non-Javadoc)
     * @see java.lang.Thread#start()
     */
    @Override
    public void start() {        
        timeDriftDetector.start();
        fleaseStage.start();
        
        try {
            fleaseStage.waitForStartup();
        } catch (Exception e) {
            listener.crashPerformed(e);
        }
        
        joinFlease();
    }
    
    /*
     * (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Layer#shutdown()
     */
    @Override
    public void shutdown() {
        exitFlease();
        
        timeDriftDetector.shutdown();
        fleaseStage.shutdown();
        
        try {
            fleaseStage.waitForShutdown();
        } catch (Exception e) {
            listener.crashPerformed(e);
        }
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Layer#_setLifeCycleListener(org.xtreemfs.foundation.LifeCycleListener)
     */
    @Override
    public void _setLifeCycleListener(LifeCycleListener listener) {
        timeDriftDetector.setLifeCycleListener(listener);
        fleaseStage.setLifeCycleListener(listener);
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.Layer#asyncShutdown()
     */
    @Override
    public void asyncShutdown() {
        timeDriftDetector.shutdown();
        fleaseStage.shutdown();
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.control.TimeDriftDetector.TimeDriftListener#driftDetected()
     */
    @Override
    public void driftDetected() {
        listener.crashPerformed(new Exception("Illegal time-drift " +
                "detected! The servers participating at the replication" +
                " are not synchronized anymore. Mutual exclusion cannot" +
                " be ensured. Replication is stopped immediately."));
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.FleaseMessageReceiver#receive(
     *          org.xtreemfs.foundation.flease.comm.FleaseMessage)
     */
    @Override
    public void receive(FleaseMessage message) {
        fleaseStage.receiveMessage(message);
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.TopLayer#registerUserInterface(org.xtreemfs.babudb.replication.LockableService)
     */
    @Override
    public void registerUserInterface(LockableService service) {
        userInterface = service;
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.TopLayer#registerReplicationInterface(org.xtreemfs.babudb.replication.LockableService)
     */
    @Override
    public void registerReplicationInterface(LockableService service) {
        replicationInterface = service;
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.control.FleaseEventListener#updateLeaseHolder(java.net.InetAddress)
     */
    @Override
    public void updateLeaseHolder(InetAddress newLeaseholder) throws Exception {
        if (thisAddress.equals(newLeaseholder)) {
            becomeMaster();
        } else {
            becomeSlave(newLeaseholder);
        }
    }
    
/*
 * private methods
 */
    
    /**
     * This server has to become the new master.
     * 
     * @throws Exception
     */
    private void becomeMaster() throws Exception {
        
        Logging.logMessage(Logging.LEVEL_INFO, this, "Becoming the replication master.");
        
        // stop all local executions
        try {
            lockAll();
            serviceInterface.changeMaster(null);
        } finally {
            unlockReplication();
        }
        
        // synchronize with other servers
        serviceInterface.synchronize(new SyncListener() {
            
            @Override
            public void synced(LogEntry entry) {
                entry.free();
                unlockUser();
            }
            
            @Override
            public void failed(LogEntry entry, Exception ex) {
                entry.free();
                
                Logging.logMessage(Logging.LEVEL_WARN, this, 
                        "Master failover did not succeed! Reseting the local lease and " +
                        "waiting for a new impulse from FLease. Reason: %s", ex.getMessage());
                
                leaseHolder.reset();
            }
        });
    }
    
    /**
     * Another server has become the master and this one has to obey.
     * 
     * @param masterAddress
     * @throws InterruptedException 
     */
    private void becomeSlave(InetAddress masterAddress) throws InterruptedException {
        
        Logging.logMessage(Logging.LEVEL_INFO, this, "Becoming a slave for %s.", 
                masterAddress.toString());
        
        lockAll();
        serviceInterface.changeMaster(masterAddress);
        unlockReplication();
        
        // user requests may only be permitted on slaves that have been synchronized with the master
        // which is only possible after the master they obey internally has been changed by this 
        // method
    }
    

    
    /**
     * Method to participate at {@link Flease}.
     */
    private void joinFlease() {
        fleaseStage.openCell(REPLICATION_CELL, fleaseParticipants, false);
    }
    
    /**
     * Method to exclude this BabuDB instance from {@link Flease}.
     */
    private void exitFlease() {
        fleaseStage.closeCell(REPLICATION_CELL);
    }
}