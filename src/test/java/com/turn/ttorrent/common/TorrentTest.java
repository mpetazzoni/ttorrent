package com.turn.ttorrent.common;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

@Test
public class TorrentTest {

	private static final Logger logger =
		LoggerFactory.getLogger(TorrentTest.class);

	@BeforeMethod(alwaysRun = true)
	protected void setUp(Method testMethod) throws Exception {
		String testName = testMethod.getDeclaringClass().getSimpleName() + "." + testMethod.getName();
		logger.trace("Test starting: " + testName);
	}

	@AfterMethod(alwaysRun = true)
	protected void tearDown(Method testMethod) throws Exception {
		String testName = testMethod.getDeclaringClass().getSimpleName() + "." + testMethod.getName();
		logger.trace("Test finished: " + testName);
	}

	public void test_create_torrent() throws Exception {
		URI announceURI = new URI("http://localhost:6969/announce");
		String createdBy = "Test";
		Torrent t = Torrent.create(new File("src/test/resources/parentFiles/file1.jar"), announceURI, createdBy);
		assertEquals(createdBy, t.getCreatedBy());
		assertEquals(announceURI, t.getAnnounceList().get(0).get(0));
	}

	public void load_torrent_created_by_utorrent() throws Exception {
		Torrent t = Torrent.load(new File("src/test/resources/torrents/file1.jar.torrent"));
		assertEquals(new URI("http://localhost:6969/announce"), t.getAnnounceList().get(0).get(0));
		assertEquals("B92D38046C76D73948E14C42DF992CAF25489D08", t.getHexInfoHash());
		assertEquals("uTorrent/3130", t.getCreatedBy());
	}
}
