/*  Copyright (c) 2009, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
                    Felix Hupfeld, Felix Langner, Zuse Institute Berlin
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the
      following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Zuse Institute Berlin nor the
      names of its contributors may be used to endorse or promote
      products derived from this software without specific prior
      written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
*/
/*
 * AUTHORS: Felix Langner (ZIB)
 */
package org.xtreemfs.babudb.api.database;

import org.xtreemfs.babudb.api.BabuDB;
import org.xtreemfs.babudb.api.exception.BabuDBException;

/**
 * User interface for {@link BabuDB} request return values.
 * 
 * @author flangner
 * @since 11/11/2009
 * @param <T>
 */
public interface DatabaseRequestResult<T> {
    
    /**
     * Sets a listener to wait asynchronously for the result of the request.
     * @param listener
     */
    public void registerListener(DatabaseRequestListener<T> listener);
    
    /**
     * Waits synchronously for the request-result.
     * 
     * @return the request result.
     * @throws BabuDBException
     *                  if the request ends with an error.
     */
    public T get() throws BabuDBException;
}
