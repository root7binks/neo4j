/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.neo4j.kernel.impl.nioneo.store.FileLock;
import org.neo4j.kernel.impl.nioneo.store.FileSystemAbstraction;

public class EphemeralFileSystemAbstraction implements FileSystemAbstraction
{
    private static final Queue<DynamicByteBuffer> memoryPool = new ConcurrentLinkedQueue<DynamicByteBuffer>();
    private final Map<String, EphemeralFileChannel> files = new HashMap<String, EphemeralFileChannel>();

    public synchronized void dispose()
    {
        for (EphemeralFileChannel file : files.values()) free(file);
        files.clear();
    }

    @Override
    protected void finalize() throws Throwable
    {
        dispose();
        super.finalize();
    }

    private void free(EphemeralFileChannel fileChannel)
    {
        if (fileChannel != null) freeBuffer(fileChannel.fileAsBuffer);
    }

    @Override
    public synchronized FileChannel open(String fileName, String mode) throws IOException
    {
        EphemeralFileChannel file = files.get(fileName);
        return file != null ? file.reset() : create(fileName);
    }

    @Override
    public FileLock tryLock(String fileName, FileChannel channel) throws IOException
    {
        return FileLock.getOsSpecificFileLock(fileName, channel);
    }

    @Override
    public synchronized FileChannel create(String fileName) throws IOException
    {
        EphemeralFileChannel file = new EphemeralFileChannel();
        free(files.put(fileName, file));
        return file;
    }

    @Override
    public long getFileSize(String fileName)
    {
        EphemeralFileChannel file = files.get(fileName);
        return file == null ? 0 : file.size();
    }

    @Override
    public boolean fileExists(String fileName)
    {
        return files.containsKey(fileName);
    }

    @Override
    public boolean deleteFile(String fileName)
    {
        free(files.remove(fileName));
        return true;
    }

    @Override
    public boolean renameFile(String from, String to) throws IOException
    {
        EphemeralFileChannel file = files.remove(from);
        if (file == null) throw new IOException("'" + from + "' doesn't exist");
        if (files.containsKey(to)) throw new IOException("'" + to + "' already exists");
        files.put(to, file);
        return true;
    }

    private static DynamicByteBuffer allocateBuffer()
    {
        DynamicByteBuffer buffer = memoryPool.poll();
        if (buffer != null)
        {
            buffer.clear();
            return buffer;
        }
        buffer = new DynamicByteBuffer();
        return buffer;
    }

    private static void freeBuffer(DynamicByteBuffer buffer)
    {
        memoryPool.add(buffer);
    }

    private static class EphemeralFileChannel extends FileChannel
    {
        private final DynamicByteBuffer fileAsBuffer = allocateBuffer();
        private final byte[] scratchPad = new byte[1024];
        private final byte[] zeroBuffer = new byte[1024];
        private int size;
        private int locked;

        @Override
        public int read(ByteBuffer dst)
        {
            int wanted = dst.limit();
            int available = min(wanted, (int) (size - position()));
            int pending = available;
            // Read up until our internal size
            while (pending > 0)
            {
                int howMuchToReadThisTime = min(pending, scratchPad.length);
                fileAsBuffer.get(scratchPad, 0, howMuchToReadThisTime);
                dst.put(scratchPad, 0, howMuchToReadThisTime);
                pending -= howMuchToReadThisTime;
            }
            // Fill the rest with zeros
            pending = available - wanted;
            while (pending > 0)
            {
                int howMuchToReadThisTime = min(pending, scratchPad.length);
                dst.put(zeroBuffer, 0, howMuchToReadThisTime);
                pending -= howMuchToReadThisTime;
            }
            return wanted;
        }

        public EphemeralFileChannel reset()
        {
            fileAsBuffer.position(0);
            return this;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(ByteBuffer src)
        {
            // TODO grow fileAsBuffer dynamically here
            int wanted = src.limit();
            int pending = wanted;
            while (pending > 0)
            {
                int howMuchToWriteThisTime = min(pending, scratchPad.length);
                src.get(scratchPad, 0, howMuchToWriteThisTime);
                fileAsBuffer.put(scratchPad, 0, howMuchToWriteThisTime);
                pending -= howMuchToWriteThisTime;
            }
            size = max(size, (int) position());
            return wanted;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position()
        {
            return fileAsBuffer.position();
        }

        @Override
        public FileChannel position(long newPosition)
        {
            fileAsBuffer.position((int) newPosition);
            return this;
        }

        @Override
        public long size()
        {
            return size;
        }

        @Override
        public FileChannel truncate(long size)
        {
            this.size = (int) size;
            return this;
        }

        @Override
        public void force(boolean metaData)
        {
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(ByteBuffer dst, long position)
        {
            long previous = position();
            position(position);
            try
            {
                return read(dst);
            } finally
            {
                position(previous);
            }
        }

        @Override
        public int write(ByteBuffer src, long position)
        {
            long previous = position();
            position(position);
            try
            {
                return write(src);
            } finally
            {
                position(previous);
            }
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException
        {
            throw new IOException("Not supported");
        }

        @Override
        public java.nio.channels.FileLock lock(long position, long size, boolean shared) throws IOException
        {
            if (locked > 0) return null;
            return new EphemeralFileLock(this);
        }

        @Override
        public java.nio.channels.FileLock tryLock(long position, long size, boolean shared) throws IOException
        {
            if (locked > 0) throw new IOException("Locked");
            return new EphemeralFileLock(this);
        }

        @Override
        protected void implCloseChannel() throws IOException
        {
        }
    }

    private static class EphemeralFileLock extends java.nio.channels.FileLock
    {
        private final EphemeralFileChannel channel;
        private boolean released;

        EphemeralFileLock(EphemeralFileChannel channel)
        {
            super(channel, 0, Long.MAX_VALUE, false);
            this.channel = channel;
            channel.locked++;
        }

        @Override
        public boolean isValid()
        {
            return !released;
        }

        @Override
        public void release() throws IOException
        {
            if (released) return;
            channel.locked--;
            released = true;
        }
    }

    /**
     * Dynamically expanding ByteBuffer substitute/wrapper. This will allocate ByteBuffers on the go
     * so that we don't have to allocate too big of a buffer up-front.
     */
    private static class DynamicByteBuffer
    {
        private static final int BUFFER_SIZE = 65536;
        private ByteBuffer buf;

        public DynamicByteBuffer()
        {
            buf = ByteBuffer.allocate(BUFFER_SIZE);
        }

        /**
         * Puts a single byte into the buffer.
         *
         * @param b
         */
        public void put(int b)
        {
            verifySize(1);
            buf.put((byte) b);
        }

        /**
         * Puts an array of bytes into the buffer.
         *
         * @param bytes
         */
        public void put(byte[] bytes)
        {
            verifySize(bytes.length);
            buf.put(bytes);
        }

        /**
         * Puts an array of bytes from the specified offset into the buffer.
         *
         * @param bytes
         * @param offset
         * @param length
         */
        public void put(byte[] bytes, int offset, int length)
        {
            verifySize(length);
            buf.put(bytes, offset, length);
        }

        /**
         * Puts the data from another {@link java.nio.ByteBuffer} into this buffer.
         *
         * @param from
         */
        public void put(ByteBuffer from)
        {
            verifySize(from.capacity() - from.remaining());
            buf.put(from);
        }

        /**
         * Puts a character into the the buffer.
         *
         * @param c
         */
        public void putCharacter(char c)
        {
            verifySize(2);
            buf.putChar(c);
        }

        /**
         * Puts a short into the buffer.
         *
         * @param s
         */
        public void putShort(short s)
        {
            verifySize(2);
            buf.putShort(s);
        }

        /**
         * Puts an integer into the buffer.
         *
         * @param i
         */
        public void putInteger(int i)
        {
            verifySize(4);
            buf.putInt(i);
        }

        /**
         * Puts a float into the buffer.
         *
         * @param f
         */
        public void putFloat(float f)
        {
            verifySize(4);
            buf.putFloat(f);
        }

        /**
         * Puts a double into the buffer.
         *
         * @param d
         */
        public void putDouble(double d)
        {
            verifySize(8);
            buf.putDouble(d);
        }

        /**
         * Puts a long into the buffer.
         *
         * @param l
         */
        public void putLong(long l)
        {
            verifySize(8);
            buf.putLong(l);
        }

        /**
         * Checks if more space needs to be allocated.
         */
        private void verifySize(int amount)
        {
            if (buf.remaining() >= amount)
            {
                return;
            }

            // Double size each time, but after 1M only increase by 1M at a time. If wanted amount is bigger than this, use that instead
            int newSize = buf.capacity() + Math.max(Math.min(this.buf.capacity(), 1024*1000), amount);
            int oldPosition = this.buf.position();
            ByteBuffer buf = ByteBuffer.allocate(newSize);
            this.buf.position(0);
            buf.put(this.buf);
            this.buf = buf;
            this.buf.position(oldPosition);
        }

        public void get(byte[] scratchPad, int i, int howMuchToReadThisTime)
        {
            buf.get(scratchPad, i, howMuchToReadThisTime);
        }

        public void clear()
        {
            this.buf.clear();
        }

        public void position(int i)
        {
            this.buf.position(i);
        }

        public long position()
        {
            return this.buf.position();
        }
    }
}