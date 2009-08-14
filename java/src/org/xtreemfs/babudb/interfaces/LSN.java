package org.xtreemfs.babudb.interfaces;

import org.xtreemfs.babudb.*;
import java.util.HashMap;
import org.xtreemfs.babudb.interfaces.utils.*;
import org.xtreemfs.include.foundation.oncrpc.utils.ONCRPCBufferWriter;
import org.xtreemfs.include.common.buffer.ReusableBuffer;




public class LSN implements org.xtreemfs.babudb.interfaces.utils.Serializable
{
    public static final int TAG = 1060;

    
    public LSN() { viewId = 0; sequenceNo = 0; }
    public LSN( int viewId, long sequenceNo ) { this.viewId = viewId; this.sequenceNo = sequenceNo; }
    public LSN( Object from_hash_map ) { viewId = 0; sequenceNo = 0; this.deserialize( from_hash_map ); }
    public LSN( Object[] from_array ) { viewId = 0; sequenceNo = 0;this.deserialize( from_array ); }

    public int getViewId() { return viewId; }
    public void setViewId( int viewId ) { this.viewId = viewId; }
    public long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo( long sequenceNo ) { this.sequenceNo = sequenceNo; }

    // Object
    public String toString()
    {
        return "LSN( " + Integer.toString( viewId ) + ", " + Long.toString( sequenceNo ) + " )";
    }

    // Serializable
    public int getTag() { return 1060; }
    public String getTypeName() { return "org::xtreemfs::babudb::interfaces::LSN"; }

    public void deserialize( Object from_hash_map )
    {
        this.deserialize( ( HashMap<String, Object> )from_hash_map );
    }
        
    public void deserialize( HashMap<String, Object> from_hash_map )
    {
        this.viewId = ( from_hash_map.get( "viewId" ) instanceof Integer ) ? ( ( Integer )from_hash_map.get( "viewId" ) ).intValue() : ( ( Long )from_hash_map.get( "viewId" ) ).intValue();
        this.sequenceNo = ( from_hash_map.get( "sequenceNo" ) instanceof Integer ) ? ( ( Integer )from_hash_map.get( "sequenceNo" ) ).longValue() : ( ( Long )from_hash_map.get( "sequenceNo" ) ).longValue();
    }
    
    public void deserialize( Object[] from_array )
    {
        this.viewId = ( from_array[0] instanceof Integer ) ? ( ( Integer )from_array[0] ).intValue() : ( ( Long )from_array[0] ).intValue();
        this.sequenceNo = ( from_array[1] instanceof Integer ) ? ( ( Integer )from_array[1] ).longValue() : ( ( Long )from_array[1] ).longValue();        
    }

    public void deserialize( ReusableBuffer buf )
    {
        viewId = buf.getInt();
        sequenceNo = buf.getLong();
    }

    public Object serialize()
    {
        HashMap<String, Object> to_hash_map = new HashMap<String, Object>();
        to_hash_map.put( "viewId", new Integer( viewId ) );
        to_hash_map.put( "sequenceNo", new Long( sequenceNo ) );
        return to_hash_map;        
    }

    public void serialize( ONCRPCBufferWriter writer ) 
    {
        writer.putInt( viewId );
        writer.putLong( sequenceNo );
    }
    
    public int calculateSize()
    {
        int my_size = 0;
        my_size += ( Integer.SIZE / 8 );
        my_size += ( Long.SIZE / 8 );
        return my_size;
    }


    private int viewId;
    private long sequenceNo;    

}

