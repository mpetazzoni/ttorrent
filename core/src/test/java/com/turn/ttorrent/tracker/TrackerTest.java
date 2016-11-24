package com.turn.ttorrent.tracker;

import com.turn.ttorrent.testutil.TempFiles;
import com.turn.ttorrent.testutil.WaitFor;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Torrent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

@Test
public class TrackerTest {

	private static final Logger logger =
		LoggerFactory.getLogger(TrackerTest.class);

	private static final String TEST_RESOURCES = "src/test/resources";
	private static final int TRACKER_PORT =
		Integer.getInteger("ttorrent.test.tracker_port", 6969);

	private Tracker tracker;
	private TempFiles tempFiles;
	private Torrent testTorrent;
	private File testFile;

	@BeforeMethod(alwaysRun = true)
	protected void setUp(Method testMethod) throws Exception {
		String testName = testMethod.getDeclaringClass().getSimpleName() + "." + testMethod.getName();
		logger.trace("Test starting: " + testName);
		tempFiles = new TempFiles();
		startTracker();
		testFile = new File(TEST_RESOURCES + "/parentFiles/file1.jar");
		String creator = String.format("%s (ttorrent)", testName);
		testTorrent = Torrent.create(testFile,
			this.tracker.getAnnounceUrl().toURI(), creator);
	}

	@AfterMethod(alwaysRun = true)
	protected void tearDown(Method testMethod) throws Exception {
		String testName = testMethod.getDeclaringClass().getSimpleName() + "." + testMethod.getName();
		stopTracker();
		tempFiles.cleanup();
		logger.trace("Test finished: " + testName);
	}

	public void test_share_and_download() throws Exception {
		final TrackedTorrent tt = this.tracker.announce(loadTorrent());
		assertEquals(0, tt.getPeers().size());

		Client seeder = createClient(completeTorrent());

		assertEquals(tt.getHexInfoHash(), seeder.getTorrent().getHexInfoHash());

		final File downloadDir = tempFiles.createTempDir();
		Client leech = createClient(incompleteTorrent(downloadDir));

		try {
			seeder.share();

			leech.download();

			waitForFileInDir(downloadDir, testFile.getName());
			assertFilesEqual(testFile, new File(downloadDir, testFile.getName()));
		} finally {
			leech.stop(true);
			seeder.stop(true);
		}
	}

	public void tracker_accepts_torrent_from_seeder() throws Exception {
		final SharedTorrent torrent = completeTorrent();
		tracker.announce(new TrackedTorrent(torrent));
		Client seeder = createClient(torrent);

		try {
			seeder.share();

			waitForSeeder(seeder.getTorrent().getHexInfoHash());

			Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
			assertEquals(1, trackedTorrents.size());

			TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
			Map<String, TrackedPeer> peers = trackedTorrent.getPeers();
			assertEquals(1, peers.size());
			assertTrue(peers.values().iterator().next().isCompleted()); // seed
			assertEquals(1, trackedTorrent.seeders());
			assertEquals(0, trackedTorrent.leechers());
		} finally {
			seeder.stop(true);
		}
	}

	public void tracker_accepts_torrent_from_leech() throws Exception {

		final File downloadDir = tempFiles.createTempDir();
		final SharedTorrent torrent = incompleteTorrent(downloadDir);
		tracker.announce(new TrackedTorrent(torrent));
		Client leech = createClient(torrent);

		try {
			leech.download();

			waitForPeers(1);

			Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
			assertEquals(1, trackedTorrents.size());

			TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
			Map<String, TrackedPeer> peers = trackedTorrent.getPeers();
			assertEquals(1, peers.size());
			assertFalse(peers.values().iterator().next().isCompleted()); // leech
			assertEquals(0, trackedTorrent.seeders());
			assertEquals(1, trackedTorrent.leechers());
		} finally {
			leech.stop(true);
		}
	}

	public void tracker_accepts_torrent_from_seeder_plus_leech() throws Exception {
		assertEquals(0, this.tracker.getTrackedTorrents().size());

		final SharedTorrent completeTorrent = completeTorrent();
		tracker.announce(new TrackedTorrent(completeTorrent));
		Client seeder = createClient(completeTorrent);

		final File downloadDir = tempFiles.createTempDir();
		final SharedTorrent incompleteTorrent = incompleteTorrent(downloadDir);
		Client leech = createClient(incompleteTorrent);

		try {
			seeder.share();
			leech.download();

			waitForFileInDir(downloadDir, testFile.getName());
		} finally {
			seeder.stop(true);
			leech.stop(true);
		}
	}

	public void large_file_download() throws Exception {

		File tempFile = tempFiles.createTempFile(201 * 1024 * 1024);

		Torrent torrent = Torrent.create(tempFile, this.tracker.getAnnounceUrl().toURI(), "Test");
		File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
		FileUtils.writeByteArrayToFile(torrentFile, torrent.getEncoded());
		tracker.announce(new TrackedTorrent(torrent));

		Client seeder = createClient(SharedTorrent.fromFile(torrentFile, tempFile.getParentFile()));

		final File downloadDir = tempFiles.createTempDir();
		Client leech = createClient(SharedTorrent.fromFile(torrentFile, downloadDir));

		try {
			seeder.share();
			leech.download();

			waitForFileInDir(downloadDir, tempFile.getName());
			assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
		} finally {
			seeder.stop(true);
			leech.stop(true);
		}
	}

	public void test_announce() throws Exception {
		assertEquals(0, this.tracker.getTrackedTorrents().size());

		this.tracker.announce(loadTorrent());

		assertEquals(1, this.tracker.getTrackedTorrents().size());
	}

	private void waitForSeeder(final String torrentHexHash) {
		new WaitFor() {
			@Override
			protected boolean condition() {
				for (TrackedTorrent tt : TrackerTest.this.tracker.getTrackedTorrents()) {
					if (tt.seeders() == 1 && tt.getHexInfoHash().equals(torrentHexHash))
						return true;
				}

				return false;
			}
		};
	}

	private void waitForPeers(final int numPeers) {
		new WaitFor() {
			@Override
			protected boolean condition() {
				for (TrackedTorrent tt : TrackerTest.this.tracker.getTrackedTorrents()) {
					if (tt.getPeers().size() == numPeers) return true;
				}

				return false;
			}
		};
	}

	private void waitForFileInDir(final File downloadDir, final String fileName) {
		new WaitFor(120 * 1000) {
			@Override
			protected boolean condition() {
				return new File(downloadDir, fileName).isFile();
			}
		};

		assertTrue(new File(downloadDir, fileName).isFile());
	}

	private TrackedTorrent loadTorrent()
			throws IOException, NoSuchAlgorithmException {
		return new TrackedTorrent(testTorrent);
	}

	private void startTracker() throws IOException {
		this.tracker = new Tracker(new InetSocketAddress(TRACKER_PORT));
		this.tracker.start();
	}

	private Client createClient(SharedTorrent torrent)
			throws IOException, NoSuchAlgorithmException, InterruptedException {
		return new Client(InetAddress.getLocalHost(), torrent);
	}

	private SharedTorrent completeTorrent()
			throws IOException, NoSuchAlgorithmException {
		File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
		return new SharedTorrent(testTorrent, parentFiles);
	}

	private SharedTorrent incompleteTorrent(File destDir)
			throws IOException, NoSuchAlgorithmException {
		return new SharedTorrent(testTorrent, destDir);
	}

	private void stopTracker() {
		this.tracker.stop();
	}

	private void assertFilesEqual(File f1, File f2) throws IOException {
		assertEquals(f1.length(), f2.length(), "Files size differs");
		Checksum c1 = FileUtils.checksum(f1, new CRC32());
		Checksum c2 = FileUtils.checksum(f2, new CRC32());
		assertEquals(c1.getValue(), c2.getValue());
	}
}
