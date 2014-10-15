/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.storage;

import com.google.common.base.Objects;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class RawFileStorage implements ByteStorage {

    private final File file;
    private final FileChannel channel;
    private boolean finished = false;

    public RawFileStorage(@Nonnull File file) throws IOException {
        this.file = file;
        this.channel = FileChannel.open(file.toPath(), EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
    }

    @Override
    public int read(ByteBuffer buffer, long offset) throws IOException {
        int bytes = channel.read(buffer, offset);
        buffer.position(buffer.limit());
        return bytes;
    }

    @Override
    public int write(ByteBuffer buffer, long offset) throws IOException {
        return channel.write(buffer, offset);
    }

    public void flush() throws IOException {
        if (channel.isOpen())
            channel.force(true);
    }

    @Override
    public void close() throws IOException {
        flush();
        if (channel.isOpen())
            channel.close();
    }

    @Override
    public void finish() throws IOException {
        if (channel.isOpen())
            channel.force(true);
        finished = true;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("file", file)
                .toString();
    }
}
