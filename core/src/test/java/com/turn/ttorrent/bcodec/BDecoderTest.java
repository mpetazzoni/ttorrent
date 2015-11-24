package com.turn.ttorrent.bcodec;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Map;

import org.junit.Test;

public class BDecoderTest {
	
	private static final byte[] TRACKER_SCRAPE_ONE_UBUNTU = new byte[] {100, 53, 58, 102, 105, 108, 101, 115, 100, 50, 48, 58, 0, 113, -95, -111, -22, -108, 39, 75, 108, 107, -20, 59, 36, 21, -47, -19, 6, -78, 50, -63, 100, 56, 58, 99, 111, 109, 112, 108, 101, 116, 101, 105, 49, 101, 49, 48, 58, 100, 111, 119, 110, 108, 111, 97, 100, 101, 100, 105, 48, 101, 49, 48, 58, 105, 110, 99, 111, 109, 112, 108, 101, 116, 101, 105, 48, 101, 52, 58, 110, 97, 109, 101, 50, 55, 58, 117, 98, 117, 110, 116, 117, 45, 56, 46, 48, 52, 45, 115, 101, 114, 118, 101, 114, 45, 105, 97, 54, 52, 46, 105, 115, 111, 101, 101, 101};
	private static final byte[] PROPER_INFOHASH = new byte[] {0, 113, -95, -111, -22, -108, 39, 75, 108, 107, -20, 59, 36, 21, -47, -19, 6, -78, 50, -63};

	@Test
	public void testProperInfoHashKeyMapping() throws Exception {
		
		BDecoder bd = new BDecoder(new ByteArrayInputStream(TRACKER_SCRAPE_ONE_UBUNTU));
		Map<String, BEValue> map = bd.bdecode().getMap().get("files").getMap();
		
		String infoHashByteString = null;
		for (String k : map.keySet()) {
			infoHashByteString = k;
			break;
		}
		
		assertArrayEquals(PROPER_INFOHASH, infoHashByteString.getBytes(Charset.forName("ISO-8859-1")));
		
	}
	
}
