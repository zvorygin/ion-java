// Copyright (c) 2010 Amazon.com, Inc.  All rights reserved.

package com.amazon.ion.impl;

import static com.amazon.ion.impl.IonConstants.BINARY_VERSION_MARKER_1_0;
import static com.amazon.ion.impl.IonConstants.BINARY_VERSION_MARKER_SIZE;

import com.amazon.ion.IonCatalog;
import com.amazon.ion.IonException;
import com.amazon.ion.IonStruct;
import com.amazon.ion.IonValue;
import com.amazon.ion.SymbolTable;
import com.amazon.ion.impl.IonBinary.BufferManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.NoSuchElementException;


public class SystemReaderImpl
    implements SystemReader
{
    private final IonSystemImpl _system;
    private final IonCatalog    _catalog;

    private Reader           _input;
    private InputStream      _stream;  // for input of binary byte data
    private IonParser        _parser;
    private BufferManager    _buffer;
    private int              _buffer_offset;

    private SymbolTable _currentSymbolTable;

    private boolean      _at_eof;
    private boolean      _currentIsHidden;
    private IonValueImpl _curr;
    private IonValueImpl _next;

    public static SystemReader makeSystemReader(IonSystemImpl system, String s)
    {
        SystemReader reader = new SystemReaderImpl(system, s);
        return reader;
    }

    public static SystemReader makeSystemReader(IonSystemImpl system,
                                         IonCatalog catalog, Reader input)
    {
        SystemReader reader = new SystemReaderImpl(system, catalog, input);
        return reader;
    }

    public static SystemReader makeSystemReader(IonSystemImpl system,
                                         IonCatalog catalog,
                                         SymbolTable initialSymboltable,
                                         Reader input)
    {
        SystemReader reader = new SystemReaderImpl(system, catalog, initialSymboltable, input);
        return reader;
    }

    public static SystemReader makeSystemReader(IonSystemImpl system,
                                         IonCatalog catalog,
                                         BufferManager buffer)
    {
        SystemReader reader = new SystemReaderImpl(system, catalog, buffer);
        return reader;
    }

    public static SystemReader makeSystemReader(IonSystemImpl system,
                                         IonCatalog catalog, InputStream stream)
    {
        SystemReader reader = new SystemReaderImpl(system, catalog, stream);
        return reader;
    }

    /**
     * Open a SystemReader over a string as the data source.  A Java
     * String is, necessarily, text input (as distinct from binary data).

     * @throws NullPointerException if any parameter is null.
     */
    private SystemReaderImpl(IonSystemImpl system, String s) {
        this(system, system.getCatalog(), new StringReader(s));
    }

    /**
     * Open a SystemReader over a character data source.  Character
     * data is necessarily text input (as distinct from binary data).
     *
     * @throws NullPointerException if any parameter is null.
     */
    private SystemReaderImpl(IonSystemImpl system,
                        IonCatalog catalog,
                        Reader input)
    {
        // TODO this should be an unmodifiable system symtab
        // but we can't yet replace it with a local symtab on-demand.
        this(system, catalog, system.newLocalSymbolTable(), input);
    }

    /**
     * Open a SystemReader over a character data source.  Character
     * data is necessarily text input (as distinct from binary data).
     *
     * @param initialSymboltable must be local, not shared.
     * @throws NullPointerException if any parameter is null.
     */
    private SystemReaderImpl(IonSystemImpl system,
                        IonCatalog catalog,
                        SymbolTable initialSymboltable,
                        Reader input)
    {
        if (system == null || catalog == null)
        {
            throw new NullPointerException();
        }
        assert initialSymboltable.isLocalTable();

        _system = system;
        _catalog = catalog;
        _currentSymbolTable = initialSymboltable;
        initialize(input, 0);
    }


    /**
     * initializes a SystemReader to read character input
     * data, which means, implicitly, the underlying data
     * is character data.
     *
     * @throws NullPointerException if input is null.
     */
    private void initialize(Reader input, int limit) {
        _input = input;
        _parser = new IonParser(_input);
        _buffer = _parser.getByteBuffer();
    }

    /**
     * Creates a new system reader using a specific catalog, reading data from
     * the start of a buffer.
     *
     * This only supports reading from a binary source.  The BufferManager
     * on input implies a buffer with binary Ion in it.
     *
     * @throws NullPointerException if any parameter is null.
     */
    @Deprecated
    private SystemReaderImpl(IonSystemImpl system,
                        IonCatalog catalog,
                        BufferManager buffer)
    {
        if (catalog == null)  // Others are dereferenced below.
        {
            throw new NullPointerException();
        }

        IonBinary.Reader reader;
        try {
            reader = buffer.reader();
            reader.sync();
            reader.setPosition(0);
        } catch (IOException e) {
            throw new IonException(e);
        }
        IonBinary.verifyBinaryVersionMarker(reader);

        _system = system;
        _catalog = catalog;

        // TODO this should be an unmodifiable system symtab
        // but we can't yet replace it with a local symtab on-demand.
        _currentSymbolTable = system.newLocalSymbolTable();
        _buffer = buffer;
        _buffer_offset = reader.position();
    }

    /**
     * Creates a new system reader using a specific catalog, reading data from
     * a Java InputStream.
     * @param system IonSystem context to create IonValues in
     * @param catalog set of shared symbol tables
     * @param stream user byte input source
     * @throws NullPointerException if any parameter is null.
     */
    private SystemReaderImpl(IonSystemImpl system,
                        IonCatalog catalog,
                        InputStream stream)
    {
        if (catalog == null)  // Others are dereferenced below.
        {
            throw new NullPointerException();
        }

        // set up the stream and the buffer manageer and reload some data
        _stream = stream;
        _buffer = new BufferManager();
        IonBinary.Reader reader = null;
        try {
            loadBuffer(BINARY_VERSION_MARKER_SIZE);
            reader = _buffer.reader(0);
        } catch (IOException e) {
            throw new IonException("initializing SystemReader", e);
        }

        IonBinary.verifyBinaryVersionMarker(reader);

        _system = system;
        _catalog = catalog;

        // TODO this should be an unmodifiable system symtab
        // but we can't yet replace it with a local symtab on-demand.
        _currentSymbolTable = system.newLocalSymbolTable();
        _buffer_offset = reader.position();

        return;
    }

    /**
     * loadBuffer reads from the associated input stream (this._stream)
     * at least enough so that length bytes are loaded in this SystemReaders
     * buffer.  It will try to read READ_AHEAD_LENGTH bytes so that we
     * don't get called on every byte (or some such nonesense).
     *
     * This will position the reader to the _buffer_offset and may well
     * reset _buffer_offset there is "used" data in the buffer (which it
     * determines by whether _buffer_offset has passed it by already - if
     * it has then the data isn't interesting any longer)
     */
    private static final int READ_AHEAD_LENGTH = 4096;
    private static final int READ_AHEAD_MAX_PEEK_REQUIRED = IonBinary._ib_VAR_INT64_LEN_MAX + 1; // type desc byte + 64 bits 7 at a time
    private void loadBuffer(int bytes_requested) throws IOException
    {
        // we should only be loading the buffer if we're
        // reading from an input stream incrementally
        assert(this._stream != null);

        int buffer_length = _buffer.buffer().size();
        assert _buffer_offset <= buffer_length;

        int bytes_to_load = bytes_requested - (buffer_length - _buffer_offset);

        // first see if we need any more bytes at all
        if (bytes_to_load < 1) return;

        // if we're going to read ahead, try to read enough
        // to make it all worthwhile (like a block - even
        // a modest sized one is worthwhile)
        if (bytes_requested < READ_AHEAD_LENGTH) {
            bytes_requested = READ_AHEAD_LENGTH;
            bytes_to_load = bytes_requested - (buffer_length - _buffer_offset);
        }

        // we'll use this to both remove any data we no longer
        // care about, and to write in the new data we need
        // to satisfy this request
        IonBinary.Writer writer = _buffer.writer(0);

        // before loading more data in, first we remove any
        // already used cruft
        // remove is smart enough so that when the pos is
        // 0 and the length to remove is the entire buffer
        // it is equivalent to truncate
        writer.remove(_buffer_offset);
        buffer_length -= _buffer_offset;

        // once we've cleared out the cruft we'll be reading at offset 0 again
        _buffer_offset = 0;

        // now we read in some data from the input stream
        // we'll try to read data in in reasonable sized chunks
        // (like a blocks worth)
        writer.setPosition(buffer_length);
        int room_in_block = writer._curr.bytesAvailableToWrite(0); //         // .blockCapacity() - buffer_length;
        if (bytes_to_load < room_in_block) {
            bytes_to_load = room_in_block;
            // FIXME but now we may load too few bytes
            // if block size < bytes_required
        }

        writer.write(_stream, bytes_to_load);

        _buffer.reader().sync();
        _buffer.reader().setPosition(_buffer_offset);
    }
    /**
     * this peeks ahead in the binary input stream and
     * reads the length (if it can).  It returns -1 if
     * it hits end of file immediately or throws an error
     * if it can't read at least the values length.
     * it then backs up in the buffer.
     * @throws IOException
     *
     */
    private int peekLength() throws IOException
    {
        int len;

        // we should only be loading the buffer if we're
        // reading from an input stream incrementally
        assert(_stream != null);

        // but if we have to read ahead, load Buffer will
        // read enough to make it worthwhile and if it
        // doesn't need to ... it won't
        loadBuffer(READ_AHEAD_MAX_PEEK_REQUIRED);

        _buffer.reader().sync();
        _buffer.reader().setPosition(_buffer_offset);

        // read 1 byte (we should have at least that much)
        int b = _buffer._reader.read();
        if (b == -1) {
            // we really did run out of data
            len = -1;
        }
        else if (b == (0xff & BINARY_VERSION_MARKER_1_0[0])){
            // back up and see if we can read a whole
            _buffer._reader.setPosition(_buffer_offset);
            IonBinary.verifyBinaryVersionMarker(_buffer._reader);
            len = BINARY_VERSION_MARKER_SIZE;
        }
        else {
            int ln = IonConstants.getLowNibble(b);
            int hn = IonConstants.getTypeCode(b);
            len = 1 + _buffer._reader.readLength(hn, ln);
        }
        _buffer._reader.setPosition(_buffer_offset);

        return len;
    }

    /**
     * returns the IonSystem that this reader is using as its system context
     * @return system associated with this reader
     */
    public IonSystemImpl getSystem() {
        return _system;
    }

    /** Returns the catalog being used by this reader. */
    public IonCatalog getCatalog() {
        return _catalog;
    }

    public SymbolTable getLocalSymbolTable() {
        return _currentSymbolTable;
    }


    public boolean canSetLocalSymbolTable()
    {
        // If parser is set, we're scanning binary data.
        return (_parser != null);
    }

    /**
     * Cannot be called between {@link #hasNext()} and {@link #next()}.
     * @param symbolTable must be local, not shared.
     */
    public void setLocalSymbolTable(SymbolTable symbolTable) {
        if (_parser == null) {
            throw new UnsupportedOperationException();
        }
        if (_next != null) {
            throw new IllegalStateException();
        }
        assert symbolTable.isLocalTable();
        _currentSymbolTable = symbolTable;
    }

    /**
     * Returns true if the iteration has more elements.
     * here we actually walk ahead and get the next value (it's
     * the only way we know if there are more and clear out the
     * various $ion noise out of the way
     */
    public boolean hasNext() {
        if (_at_eof) return false;
        if (_next != null) return true;
        return (prefetch() != null);
    }

    private IonValue prefetch()
    {
        assert !_at_eof && _next == null;

        assert _buffer_offset <= _buffer.buffer().size();

        BufferManager buffer = _buffer;
        // just to make the other code easier to read, and write

        // ok, now we walk on ahead
        if (_stream != null) {
            // if we have a stream to read from this is reading streaming binary
            // and we alway read 1 top level value at a time by prereading to get
            // the length and the loading at least that much in from the stream
            // in this case we don't care if there happens to be data in the
            // buffer for us, since we might have over-read on the last value
            // and we have a partial loaded - we'll check that out by looking
            // at the length

            int len;
            try {
                len = peekLength();
                if (len < 1) {
                    _at_eof = true;
                }
                else {
                    loadBuffer(len);
                }
            } catch (IOException e) {
                throw new IonException(e);
            }

        }
        else if (buffer.buffer().size() <= _buffer_offset) {
            // if the buffer has run out of data then we need to refill it
            // this happens when we're parsing text (if we were reading
            // binary either the data would be loaded or we're at eof)

            // we used up the buffer we've seen so far ...
            // so parse another value out of the input
            if (_parser != null) {
                boolean freshBuffer = _buffer_offset == 0;
                // cas 22 apr 2008:
                freshBuffer = false; // this is really the "write magic cookie" flag
                _parser.parse(_currentSymbolTable
                              ,_buffer_offset
                              ,freshBuffer
                              ,0
                );
                if (freshBuffer) {
                    // We wrote a magic cookie; skip it.
                    _buffer_offset = BINARY_VERSION_MARKER_SIZE;
                }
            }
            if (buffer.buffer().size() <= _buffer_offset) {
                // we didn't make any progress,
                // so there's no more data for us
                _at_eof = true;
            }
        }

        // now that we've got a value in the buffer (well we have one if we're not at eof)
        if (!_at_eof) {
            // there is some sort a value, we'll get it and check it out
            // until we find something we like
            IonValueImpl value =
                IonValueImpl.makeValueFromBuffer(0
                                                 ,_buffer_offset
                                                 ,buffer
                                                 ,this._currentSymbolTable
                                                 ,null
                                                 ,_system
            );

            // move along on the buffer
            _buffer_offset = value.pos_getOffsetofNextValue();

            _next = value;
        }

        return _next;
    }


    public IonValueImpl next() {
        if (! _at_eof) {
            _curr = null;
            if (_next == null) {
                prefetch();
            }
            if (_next != null) {
                _curr = _next;
                _next = null;

                checkCurrentForHiddens();

                return _curr;
            }
        }
        throw new NoSuchElementException();
    }

    private void checkCurrentForHiddens()
    {
        final IonValue curr = _curr;

        if (IonSystemImpl.valueIsLocalSymbolTable(curr))
        {
            _currentSymbolTable =
                UnifiedSymbolTable.makeNewLocalSymbolTable(_system.getSystemSymbolTable(), _catalog, (IonStruct) curr);
            _currentIsHidden = true;
        }
        else if (_system.valueIsSystemId(curr))
        {
            assert curr.getSymbolTable().isLocalTable(); // Unfortunately
            // This makes the value dirty:
            _system.blessSystemIdSymbol((IonSymbolImpl) curr);

            SymbolTable identifiedSystemTable = curr.getSymbolTable();
            _currentSymbolTable =
                _system.newLocalSymbolTable(identifiedSystemTable);
            _currentIsHidden = true;
        }
        else {
            _currentIsHidden = false;
        }
    }


    /**
     * Only valid after call to {@link #next()}.
     */
    public boolean currentIsHidden() {
        if (_curr == null) {
            throw new IllegalStateException();
        }
        return _currentIsHidden;
    }


    public void remove() {
        throw new UnsupportedOperationException();
    }

    public BufferManager getBuffer() {
        return _buffer;
    }

    public void resetBuffer() {
        if (this._buffer_offset > BINARY_VERSION_MARKER_SIZE) {
            this._buffer_offset = BINARY_VERSION_MARKER_SIZE;
            try {
                _buffer.writer(BINARY_VERSION_MARKER_SIZE).truncate();
            }
            catch (IOException e) {
                throw new IonException(e);
            }
        }
    }

    public void close()
    {
        _at_eof = true;
        _curr = _next = null;

        if (_input != null)
        {
            try
            {
                _input.close();
            }
            catch (IOException e)
            {
                throw new IonException("Error closing reader: "
                                           + e.getMessage(),
                                       e);
            }
            finally
            {
                _input = null;
            }
        }
    }
}
