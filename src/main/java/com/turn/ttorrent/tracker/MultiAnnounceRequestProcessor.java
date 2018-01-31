package com.turn.ttorrent.tracker;

import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.http.HTTPTrackerErrorMessage;
import org.simpleframework.http.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiAnnounceRequestProcessor {

  private final TrackerRequestProcessor myTrackerRequestProcessor;

  private static final Logger logger =
          LoggerFactory.getLogger(MultiAnnounceRequestProcessor.class);

  public MultiAnnounceRequestProcessor(TrackerRequestProcessor myTrackerRequestProcessor) {
    this.myTrackerRequestProcessor = myTrackerRequestProcessor;
  }

  public void process(final String body, final String url, final String hostAddress, final TrackerRequestProcessor.RequestHandler requestHandler) throws IOException {

    final List<ByteBuffer> responses = new ArrayList<ByteBuffer>();
    final AtomicBoolean isAnySuccess = new AtomicBoolean(false);
    final AtomicInteger totalByteBuffersSize = new AtomicInteger();
    for (String s : body.split("\n")) {
      myTrackerRequestProcessor.process(s, hostAddress, new TrackerRequestProcessor.RequestHandler() {
        @Override
        public void serveResponse(int code, String description, ByteBuffer responseData) {
          isAnySuccess.set(isAnySuccess.get() || (code == Status.OK.getCode()));
          totalByteBuffersSize.addAndGet(responseData.capacity());
          responses.add(responseData);
        }

        @Override
        public ConcurrentMap<String, TrackedTorrent> getTorrentsMap() {
          return requestHandler.getTorrentsMap();
        }
      });
    }
    if (responses.isEmpty()) {
      ByteBuffer res = ByteBuffer.allocate(0);
      try {
        res = HTTPTrackerErrorMessage.craft("").getData();
      } catch (TrackerMessage.MessageValidationException e) {
        logger.warn("Could not craft tracker error message!", e);
      }
      requestHandler.serveResponse(Status.BAD_REQUEST.getCode(), "", res);
      return;
    }
    ByteBuffer multiplyResponse = ByteBuffer.allocate(totalByteBuffersSize.get() + responses.size() - 1);
    Iterator<ByteBuffer> iterator = responses.iterator();
    while (iterator.hasNext()) {
      final ByteBuffer buffer = iterator.next();
      buffer.rewind();
      if (!iterator.hasNext()) {
        //it's last
        multiplyResponse.put(buffer);
        continue;
      }
      multiplyResponse.put(buffer);
      multiplyResponse.put((byte)0);//separator
    }
    requestHandler.serveResponse(isAnySuccess.get() ? Status.OK.getCode() : Status.BAD_REQUEST.getCode(), "", multiplyResponse);
  }
}
