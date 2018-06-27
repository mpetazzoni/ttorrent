import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class BDecoderTest {

  @Test
  public void testDecodeNumbers() throws IOException {

    testNumber(0);
    testNumber(1);
    testNumber(Integer.MAX_VALUE);
    testNumber(1234567);
    testNumber(-1);
    testNumber(-100);
    testNumber(Integer.MIN_VALUE);
    testNumber(Long.MAX_VALUE);
    testNumber(Long.MIN_VALUE);

    //by specification number with lead zero it's incorrect value
    testBadNumber("00");
    testBadNumber("01234");
    testBadNumber("000");
    testBadNumber("0001");

  }

  private void testBadNumber(String number) throws IOException {
    try {
      BDecoder.bdecode(numberToBEPBytes(number));
    } catch (InvalidBEncodingException e) {
      return;
    }
    fail("Value " + number + " is incorrect by BEP specification but is was parsed correctly");
  }

  private void testNumber(long value) throws IOException {
    assertEquals(BDecoder.bdecode(numberToBEPBytes(value)).getLong(), value);
  }

  private ByteBuffer numberToBEPBytes(long value) throws UnsupportedEncodingException {
    return ByteBuffer.wrap(("i" + value + "e").getBytes("ASCII"));
  }

  private ByteBuffer numberToBEPBytes(String value) throws UnsupportedEncodingException {
    return ByteBuffer.wrap(("i" + value + "e").getBytes("ASCII"));
  }

}
