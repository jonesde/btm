package bitronix.tm.mock.resource;

import bitronix.tm.internal.BitronixXAException;
import bitronix.tm.mock.events.*;
import bitronix.tm.mock.resource.jdbc.MockXADataSource;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

/**
 * (c) Bitronix, 19-d�c.-2005
 *
 * @author lorban
 */
public class MockXAResource implements XAResource {

    private int prepareRc = XAResource.XA_OK;
    private int transactiontimeout;
    private MockXADataSource xads;

    private XAException prepareException;
    private XAException commitException;
    private XAException rollbackException;
    private RuntimeException prepareRuntimeException;

    public MockXAResource(MockXADataSource xads) {
        this.xads = xads;
    }


    public void setPrepareRc(int prepareRc) {
        this.prepareRc = prepareRc;
    }

    public void addInDoubtXid(Xid xid) {
        xads.addInDoubtXid(xid);
    }

    private EventRecorder getEventRecorder() {
        return EventRecorder.getEventRecorder(this);
    }

    /*
    Interface implementation
    */

    public int getTransactionTimeout() throws XAException {
        return transactiontimeout;
    }

    public boolean setTransactionTimeout(int i) throws XAException {
        this.transactiontimeout = i;
        return true;
    }

    public boolean isSameRM(XAResource xaResource) throws XAException {
        return false;
    }

    public Xid[] recover(int flag) throws XAException {
        if (xads == null)
            return new Xid[0];
        return xads.getInDoubtXids();
    }

    public int prepare(Xid xid) throws XAException {
        if (prepareException != null) {
            getEventRecorder().addEvent(new XAResourcePrepareEvent(this, prepareException, xid, -1));
            prepareException.fillInStackTrace();
            throw prepareException;
        }
        if (prepareRuntimeException != null) {
            prepareRuntimeException.fillInStackTrace();
            getEventRecorder().addEvent(new XAResourcePrepareEvent(this, prepareRuntimeException, xid, -1));
            throw prepareRuntimeException;
        }
        getEventRecorder().addEvent(new XAResourcePrepareEvent(this, xid, prepareRc));
        return prepareRc;
    }

    public void forget(Xid xid) throws XAException {
        getEventRecorder().addEvent(new XAResourceForgetEvent(this, xid));
        boolean found = xads.removeInDoubtXid(xid);
        if (!found)
            throw new BitronixXAException("unknown XID: " + xid, XAException.XAER_INVAL);
    }

    public void rollback(Xid xid) throws XAException {
        getEventRecorder().addEvent(new XAResourceRollbackEvent(this, rollbackException, xid));
        if (rollbackException != null)
            throw rollbackException;
        if (xads != null) xads.removeInDoubtXid(xid);
    }

    public void end(Xid xid, int flag) throws XAException {
        getEventRecorder().addEvent(new XAResourceEndEvent(this, xid, flag));
    }

    public void start(Xid xid, int flag) throws XAException {
        getEventRecorder().addEvent(new XAResourceStartEvent(this, xid, flag));
    }

    public void commit(Xid xid, boolean b) throws XAException {
        getEventRecorder().addEvent(new XAResourceCommitEvent(this, commitException, xid, b));
        if (commitException != null)
            throw commitException;
        if (xads != null) xads.removeInDoubtXid(xid);
    }

    public void setPrepareException(XAException prepareException) {
        this.prepareException = prepareException;
    }

    public void setPrepareException(RuntimeException prepareException) {
        this.prepareRuntimeException = prepareException;
    }

    public void setCommitException(XAException commitException) {
        this.commitException = commitException;
    }

    public void setRollbackException(XAException rollbackException) {
        this.rollbackException = rollbackException;
    }
}