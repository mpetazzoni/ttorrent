package com.turn.ttorrent.network.keyProcessors;

import com.turn.ttorrent.network.WriteAttachment;
import com.turn.ttorrent.network.WriteListener;
import com.turn.ttorrent.network.WriteTask;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;

import static org.mockito.Mockito.*;

@Test
public class WritableKeyProcessorTest {

  private SelectionKey myKey;
  private SocketChannel myChannel;
  private WritableKeyProcessor myWritableKeyProcessor;
  private WriteAttachment myWriteAttachment;
  private BlockingQueue<WriteTask> myQueue;


  @SuppressWarnings("unchecked")
  @BeforeMethod
  public void setUp() throws Exception {
    myKey = mock(SelectionKey.class);
    myChannel = mock(SocketChannel.class);
    myWritableKeyProcessor = new WritableKeyProcessor();
    when(myKey.channel()).thenReturn(myChannel);
    when(myKey.interestOps()).thenReturn(SelectionKey.OP_WRITE);
    myWriteAttachment = mock(WriteAttachment.class);
    myQueue = mock(BlockingQueue.class);
  }

  public void testThatOnWriteDoneInvoked() throws Exception {
    final ByteBuffer data = ByteBuffer.allocate(10);

    //imitate writing byte buffer
    when(myChannel.write(eq(data))).then(new Answer<Integer>() {
      @Override
      public Integer answer(InvocationOnMock invocationOnMock) throws Throwable {
        data.position(data.capacity());
        return data.capacity();
      }
    });

    WriteListener listener = mock(WriteListener.class);

    when(myQueue.peek()).thenReturn(new WriteTask(myChannel, data, listener));
    when(myWriteAttachment.getWriteTasks()).thenReturn(myQueue);

    myKey.attach(myWriteAttachment);

    myWritableKeyProcessor.process(myKey);

    verify(listener).onWriteDone();
  }

  public void testThatOnWriteFailedInvokedIfChannelThrowException() throws Exception {
    when(myChannel.write(any(ByteBuffer.class))).thenThrow(new IOException());

    WriteListener listener = mock(WriteListener.class);

    when(myQueue.peek()).thenReturn(new WriteTask(myChannel, ByteBuffer.allocate(1), listener));
    when(myWriteAttachment.getWriteTasks()).thenReturn(myQueue);
    myKey.attach(myWriteAttachment);

    myWritableKeyProcessor.process(myKey);

    verify(listener).onWriteFailed(anyString(), any(Throwable.class));
  }

  public void checkThatWriteTaskDoesntRemovedIfBufferIsNotWrittenInOneStep() throws Exception {
    final ByteBuffer data = ByteBuffer.allocate(10);

    //imitate writing only one byte of byte buffer
    when(myChannel.write(eq(data))).then(new Answer<Integer>() {
      @Override
      public Integer answer(InvocationOnMock invocationOnMock) throws Throwable {
        data.position(data.capacity() - 1);
        return data.position();
      }
    });

    WriteListener listener = mock(WriteListener.class);

    when(myQueue.peek()).thenReturn(new WriteTask(myChannel, data, listener));
    when(myWriteAttachment.getWriteTasks()).thenReturn(myQueue);

    myKey.attach(myWriteAttachment);

    myWritableKeyProcessor.process(myKey);

    verify(listener, never()).onWriteDone();
  }
}
