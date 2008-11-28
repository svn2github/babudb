/*
 * Copyright (c) 2008, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
*/

package org.xtreemfs.babudb.index.reader;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Map.Entry;

import org.xtreemfs.babudb.index.ByteRange;
import org.xtreemfs.babudb.index.ByteRangeComparator;

public class BlockReader {
    
    public static final int     KEYS_OFFSET = 4 * Integer.SIZE / 8;
    
    private ByteBuffer          buffer;
    
    private int                 position;
    
    private int                 limit;
    
    private ByteRangeComparator comp;
    
    private MiniPage            keys;
    
    private MiniPage            values;
    
    private int                 numEntries;
    
    public BlockReader(ByteBuffer buf, int position, int limit, ByteRangeComparator comp) {
        
        this.buffer = buf;
        this.position = position;
        this.limit = limit;
        this.comp = comp;
        
        int keysOffset = position + KEYS_OFFSET;
        int valsOffset = position + buf.getInt(position);
        numEntries = buf.getInt(position + 4);
        int keyEntrySize = buf.getInt(position + 8);
        int valEntrySize = buf.getInt(position + 12);
        
        keys = keyEntrySize == -1 ? new VarLenMiniPage(numEntries, buf, keysOffset, valsOffset,
            comp) : new FixedLenMiniPage(keyEntrySize, numEntries, buf, keysOffset, valsOffset,
            comp);
        values = valEntrySize == -1 ? new VarLenMiniPage(numEntries, buf, valsOffset, limit, comp)
            : new FixedLenMiniPage(valEntrySize, numEntries, buf, valsOffset, limit, comp);
    }
    
    public BlockReader clone() {
        buffer.position(0);
        return new BlockReader(buffer.slice(), position, limit, comp);
    }
    
    public ByteRange lookup(byte[] key) {
        
        int index = keys.getPosition(key);
        if (index == -1)
            return null;
        
        return values.getEntry(index);
    }
    
    public Iterator<Entry<ByteRange, ByteRange>> rangeLookup(byte[] from, byte[] to) {
        
        final int startIndex;
        final int endIndex;
        
        {
            startIndex = keys.getTopPosition(from);
            endIndex = keys.getBottomPosition(to);
        }
        
        return new Iterator<Entry<ByteRange, ByteRange>>() {
            
            int currentIndex = startIndex;
            
            @Override
            public boolean hasNext() {
                return currentIndex <= endIndex;
            }
            
            @Override
            public Entry<ByteRange, ByteRange> next() {
                
                if (!hasNext())
                    throw new NoSuchElementException();
                
                Entry<ByteRange, ByteRange> entry = new Entry<ByteRange, ByteRange>() {
                    
                    final ByteRange key   = keys.getEntry(currentIndex);
                    
                    final ByteRange value = values.getEntry(currentIndex);
                    
                    @Override
                    public ByteRange getValue() {
                        return value;
                    }
                    
                    @Override
                    public ByteRange getKey() {
                        return key;
                    }
                    
                    @Override
                    public ByteRange setValue(ByteRange value) {
                        throw new UnsupportedOperationException();
                    }
                };
                
                currentIndex++;
                
                return entry;
            }
            
            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
            
        };
    }
    
    public MiniPage getKeys() {
        return keys;
    }
    
    public MiniPage getValues() {
        return values;
    }
    
    public int getNumEntries() {
        return numEntries;
    }
    
}
