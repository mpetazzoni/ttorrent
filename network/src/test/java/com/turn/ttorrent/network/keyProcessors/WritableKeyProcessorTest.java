package com.turn.ttorrent.network.keyProcessors;

import com.turn.ttorrent.network.WriteAttachment;
import com.turn.ttorrent.network.WriteListener;
import com.turn.ttorrent.network.WriteTask;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;

import static org.mockito.Mockito.*;

@Test
public class WritableKeyProcessorTest {

  private SelectionKey myKey;


  @BeforeMethod
  public void setUp() throws Exception {
    myKey = mock(SelectionKey.class);
  }

  public void testThatOnWriteDoneInvoked() throws Exception {
    WritableKeyProcessor writableKeyProcessor = new WritableKeyProcessor();

    SocketChannel channel = mock(SocketChannel.class);
    WriteAttachment writeAttachment = mock(WriteAttachment.class);
    BlockingQueue<WriteTask> queue = mock(BlockingQueue.class);
    WriteListener listener = mock(WriteListener.class);

    when(myKey.channel()).thenReturn(channel);
    when(myKey.interestOps()).thenReturn(SelectionKey.OP_WRITE);
    when(queue.peek()).thenReturn(new WriteTask(channel, ByteBuffer.allocate(0), listener));
    when(writeAttachment.getWriteTasks()).thenReturn(queue);

    myKey.attach(writeAttachment);

    writableKeyProcessor.process(myKey);

    verify(listener).onWriteDone();
  }

}
