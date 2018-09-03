package com.turn.ttorrent.tracker;

import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.Utils;
import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.http.HTTPAnnounceResponseMessage;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test
public class MultiAnnounceRequestProcessorTest {

  private Tracker tracker;
  private TempFiles tempFiles;


  public MultiAnnounceRequestProcessorTest() {
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS}] %6p - %20.20c - %m %n")));
    Logger.getRootLogger().setLevel(Utils.getLogLevel());
  }

  @BeforeMethod
  protected void setUp() throws Exception {
    tempFiles = new TempFiles();
    startTracker();
  }

  public void processCorrectTest() throws TrackerMessage.MessageValidationException, IOException {
    final URL url = new URL("http://localhost:6969/announce");

    final String urlTemplate = url.toString() +
            "?info_hash={hash}" +
            "&peer_id=ABCDEFGHIJKLMNOPQRST" +
            "&ip={ip}" +
            "&port={port}" +
            "&downloaded=1234" +
            "&left=0" +
            "&event=started";

    final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    StringBuilder requestString = new StringBuilder();
    for (int i = 0; i < 5; i++) {
      if (i != 0) {
        requestString.append("\n");
      }
      requestString.append(getUrlFromTemplate(urlTemplate, "1" + i, "127.0.0.1", 6881));
    }
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
    connection.setDoOutput(true);
    connection.getOutputStream().write(requestString.toString().getBytes("UTF-8"));

    final InputStream inputStream = connection.getInputStream();

    final BEValue bdecode = BDecoder.bdecode(inputStream);

    assertEquals(tracker.getTrackedTorrents().size(), 5);
    assertEquals(bdecode.getList().size(), 5);

    for (BEValue beValue : bdecode.getList()) {

      final HTTPAnnounceResponseMessage responseMessage = HTTPAnnounceResponseMessage.parse(beValue);
      assertTrue(responseMessage.getPeers().isEmpty());
      assertEquals(1, responseMessage.getComplete());
      assertEquals(0, responseMessage.getIncomplete());
    }
  }

  private String getUrlFromTemplate(String template, String hash, String ip, int port) {
    return template.replace("{hash}", hash).replace("{ip}", ip).replace("{port}", String.valueOf(port));
  }


  private void startTracker() throws IOException {
    this.tracker = new Tracker(6969);
    tracker.setAnnounceInterval(5);
    tracker.setPeerCollectorExpireTimeout(10);
    this.tracker.start(true);
  }

  private void stopTracker() {
    this.tracker.stop();
  }

  @AfterMethod
  protected void tearDown() throws Exception {
    stopTracker();
    tempFiles.cleanup();
  }

}
