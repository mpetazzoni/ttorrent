package com.turn.ttorrent.tracker;

import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import org.apache.commons.io.IOUtils;
import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.core.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

/**
 * @author Sergey.Pak
 * Date: 8/12/13
 * Time: 8:25 PM
 */
public class TrackerServiceContainer implements Container {

  private static final Logger logger =
          TorrentLoggerFactory.getLogger();

  private TrackerRequestProcessor myRequestProcessor;
  private final MultiAnnounceRequestProcessor myMultiAnnounceRequestProcessor;

  public TrackerServiceContainer(final TrackerRequestProcessor requestProcessor,
                                 final MultiAnnounceRequestProcessor multiAnnounceRequestProcessor) {
    myRequestProcessor = requestProcessor;
    myMultiAnnounceRequestProcessor = multiAnnounceRequestProcessor;
  }

  /**
   * Handle the incoming request on the tracker service.
   * <p/>
   * <p>
   * This makes sure the request is made to the tracker's announce URL, and
   * delegates handling of the request to the <em>process()</em> method after
   * preparing the response object.
   * </p>
   *
   * @param request  The incoming HTTP request.
   * @param response The response object.
   */
  @Override
  public void handle(Request request, final Response response) {
    // Reject non-announce requests
    if (!Tracker.ANNOUNCE_URL.equals(request.getPath().toString())) {
      response.setCode(404);
      response.setText("Not Found");
      return;
    }

    OutputStream body = null;
    try {
      body = response.getOutputStream();

      response.set("Content-Type", "text/plain");
      response.set("Server", "");
      response.setDate("Date", System.currentTimeMillis());

      if ("GET".equalsIgnoreCase(request.getMethod())) {

        myRequestProcessor.process(request.getAddress().toString(), request.getClientAddress().getAddress().getHostAddress(),
                getRequestHandler(response));
      } else {
        myMultiAnnounceRequestProcessor.process(request.getContent(), request.getAddress().toString(),
                request.getClientAddress().getAddress().getHostAddress(), getRequestHandler(response));
      }
      body.flush();
    } catch (IOException ioe) {
      logger.info("Error while writing response: {}!", ioe.getMessage());
    } catch (Throwable t) {
      LoggerUtils.errorAndDebugDetails(logger, "error in processing request {}", request, t);
    } finally {
      IOUtils.closeQuietly(body);
    }

  }

  private TrackerRequestProcessor.RequestHandler getRequestHandler(final Response response) {
    return new TrackerRequestProcessor.RequestHandler() {
      @Override
      public void serveResponse(int code, String description, ByteBuffer responseData) {
        response.setCode(code);
        response.setText(description);
        try {
          responseData.rewind();
          final WritableByteChannel channel = Channels.newChannel(response.getOutputStream());
          channel.write(responseData);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    };
  }

  public void setAcceptForeignTorrents(boolean acceptForeignTorrents) {
    myRequestProcessor.setAcceptForeignTorrents(acceptForeignTorrents);
  }

  public void setAnnounceInterval(int announceInterval) {
    myRequestProcessor.setAnnounceInterval(announceInterval);
  }
}
