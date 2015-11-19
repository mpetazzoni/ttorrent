package com.turn.ttorrent.client;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.http.client.ClientProtocolException;
import org.junit.Test;

public class UrlSharedTorrentTest {
	
	public String TEST_URL = "http://releases.ubuntu.com/14.04.3/ubuntu-14.04.3-desktop-amd64.iso.torrent";

	@Test
	public void testResolveTorrentUrl() throws ClientProtocolException, IOException, URISyntaxException {
		byte[] resolveTorrentUrl = UrlSharedTorrent.resolveTorrentUrl(new URI(TEST_URL));
		assertNotNull(resolveTorrentUrl);
		assertTrue(resolveTorrentUrl.length > 0);
	}
	
}
