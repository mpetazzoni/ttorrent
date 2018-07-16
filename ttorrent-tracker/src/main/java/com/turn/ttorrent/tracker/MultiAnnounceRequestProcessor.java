package com.turn.ttorrent.tracker;

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.http.HTTPTrackerErrorMessage;
import org.simpleframework.http.Status;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MultiAnnounceRequestProcessor {

  private final TrackerRequestProcessor myTrackerRequestProcessor;

  private static final Logger logger =
          TorrentLoggerFactory.getLogger();

  public MultiAnnounceRequestProcessor(TrackerRequestProcessor trackerRequestProcessor) {
    myTrackerRequestProcessor = trackerRequestProcessor;
  }

  public void process(final String body, final String url, final String hostAddress, final TrackerRequestProcessor.RequestHandler requestHandler) throws IOException {

    final List<BEValue> responseMessages = new ArrayList<BEValue>();
    final AtomicBoolean isAnySuccess = new AtomicBoolean(false);
    for (String s : body.split("\n")) {
      myTrackerRequestProcessor.process(s, hostAddress, new TrackerRequestProcessor.RequestHandler() {
        @Override
        public void serveResponse(int code, String description, ByteBuffer responseData) {
          isAnySuccess.set(isAnySuccess.get() || (code == Status.OK.getCode()));
          try {
            responseMessages.add(BDecoder.bdecode(responseData));
          } catch (IOException e) {
            logger.warn("cannot decode message from byte buffer");
          }
        }
      });
    }
    if (responseMessages.isEmpty()) {
      ByteBuffer res;
      Status status;
      res = HTTPTrackerErrorMessage.craft("").getData();
      status = Status.BAD_REQUEST;
      requestHandler.serveResponse(status.getCode(), "", res);
      return;
    }
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    BEncoder.bencode(responseMessages, out);
    requestHandler.serveResponse(isAnySuccess.get() ? Status.OK.getCode() : Status.BAD_REQUEST.getCode(), "", ByteBuffer.wrap(out.toByteArray()));
  }
}
