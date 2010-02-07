/*
 * Copyright (c) 2008 Amazon.com, Inc.  All rights reserved.
 */

package com.amazon.ion;


/**
 *
 */
public class LoadBinaryBytesSystemProcessingTest
    extends DatagramIteratorSystemProcessingTest
{
    private byte[] myBytes;

    @Override
    protected void prepare(String text)
        throws Exception
    {
        myBytes = encode(text);
    }

    @Override
    protected IonDatagram load()
        throws Exception
    {
        IonLoader loader = loader();
        IonDatagram datagram = loader.load(myBytes);
        return datagram;
    }

    @Override
    protected boolean checkMissingSymbol(String expected, int expectedSymbolTableSid, int expectedLocalSid)
        throws Exception
    {
        // we're working on a datagram, the symbol will have
        // been materialized during the datagram construction
        checkSymbol("$" + expectedSymbolTableSid, expectedSymbolTableSid);
        //checkSymbol(expected, expectedSymbolTableSid);

        // when missing from a shared table the symbol
        // will have been added to the local symbols
        return false;
    }
}
