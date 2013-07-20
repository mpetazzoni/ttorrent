package com.turn.ttorrent.tracker;

import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Torrent;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.spi.RootLogger;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static org.testng.Assert.*;

@Test
public class TrackerTest {
    private static final String TEST_RESOURCES = "src/test/resources";
    private Tracker tracker;
    private TempFiles tempFiles;

    @BeforeMethod
    protected void setUp() throws Exception {
        BasicConfigurator.configure();
        RootLogger.getRootLogger().setLevel(Level.INFO);
        tempFiles = new TempFiles();
        startTracker();
    }

    public void test_share_and_download() throws IOException, NoSuchAlgorithmException, InterruptedException {
        final TrackedTorrent tt = this.tracker.announce(loadTorrent("file1.jar.torrent"));
        assertEquals(0, tt.getPeers().size());

        Client seeder = createClient(getCompleteTorrent("file1.jar.torrent"));

        assertEquals(tt.getHexInfoHash(), seeder.getTorrent().getHexInfoHash());

        final File downloadDir = tempFiles.createTempDir();
        Client leech = createClient(getIncompleteTorrent("file1.jar.torrent", downloadDir));

        try {
            seeder.share();

            leech.download();

            waitForFileInDir(downloadDir, "file1.jar");
            assertFilesEqual(new File(TEST_RESOURCES + "/parentFiles/file1.jar"), new File(downloadDir, "file1.jar"));
        } finally {
            leech.stop(true);
            seeder.stop(true);
        }
    }

    public void tracker_accepts_torrent_from_seeder() throws IOException, NoSuchAlgorithmException, InterruptedException {
        final SharedTorrent torrent = getCompleteTorrent("file1.jar.torrent");
        tracker.announce(new TrackedTorrent(torrent));
        Client seeder = createClient(torrent);

        try {
            seeder.share();

            waitForSeeder(seeder.getTorrent().getInfoHash());

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

    public void tracker_accepts_torrent_from_leech() throws IOException, NoSuchAlgorithmException, InterruptedException {

        final File downloadDir = tempFiles.createTempDir();
        final SharedTorrent torrent = getIncompleteTorrent("file1.jar.torrent", downloadDir);
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

    @Test(invocationCount = 20) //TODO: this test is unstable. Figure out the reason
    public void tracker_accepts_torrent_from_seeder_plus_leech() throws IOException, NoSuchAlgorithmException, InterruptedException {
        assertEquals(0, this.tracker.getTrackedTorrents().size());

        final SharedTorrent completeTorrent = getCompleteTorrent("file1.jar.torrent");
        tracker.announce(new TrackedTorrent(completeTorrent));
        Client seeder = createClient(completeTorrent);

        final File downloadDir = tempFiles.createTempDir();
        final SharedTorrent incompleteTorrent = getIncompleteTorrent("file1.jar.torrent", downloadDir);
        Client leech = createClient(incompleteTorrent);

        try {
            seeder.share();
            leech.download();

            waitForFileInDir(downloadDir, "file1.jar");
        } finally {
            seeder.stop(true);
            leech.stop(true);
        }
    }


    @Test(invocationCount = 20) //TODO: this test is unstable. Figure out the reason
    public void large_file_download() throws IOException, URISyntaxException, NoSuchAlgorithmException, InterruptedException {


        File tempFile = tempFiles.createTempFile(201 * 1024 * 1024);

        Torrent torrent = Torrent.create(tempFile, this.tracker.getAnnounceUrl().toURI(), "Test");
        File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
        torrent.save(torrentFile);
        tracker.announce(new TrackedTorrent(torrent));

        Client seeder = createClient(SharedTorrent.fromFile(torrentFile, tempFile.getParentFile()));

        final File downloadDir = tempFiles.createTempDir();
        Client leech = createClient(SharedTorrent.fromFile(torrentFile, downloadDir));

        try {
            seeder.share();
            leech.download();

            waitForFileInDir(downloadDir, tempFile.getName(), 60*1000, 1000);
            assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
        } finally {
            seeder.stop(true);
            leech.stop(true);
        }
    }

    public void test_announce() throws IOException, NoSuchAlgorithmException {
        assertEquals(0, this.tracker.getTrackedTorrents().size());

        this.tracker.announce(loadTorrent("file1.jar.torrent"));

        assertEquals(1, this.tracker.getTrackedTorrents().size());
    }

    public void test_foreign_torrent() throws IOException, NoSuchAlgorithmException, InterruptedException, URISyntaxException {

        tracker.setAcceptForeignTorrents(true);
        final File tempFile = tempFiles.createTempFile(1025 * 1024);

        final Torrent torrent = Torrent.create(tempFile, this.tracker.getAnnounceUrl().toURI(), "Test");
        File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
        torrent.save(torrentFile);

        Client seeder = createClient(SharedTorrent.fromFile(torrentFile, tempFile.getParentFile()));

        final File downloadDir = tempFiles.createTempDir();
        Client leech = createClient(SharedTorrent.fromFile(torrentFile, downloadDir));

        try {
            seeder.share();
            leech.download();

            final WaitFor waitFor = new WaitFor(1000, 100) {
                @Override
                protected boolean condition() {
                    final Collection<TrackedTorrent> trackedTorrents = tracker.getTrackedTorrents();
                    return (trackedTorrents.size() > 0 &&
                            trackedTorrents.iterator().next().getHexInfoHash().equals(torrent.getHexInfoHash()));
                }
            };
            assertTrue(waitFor.isConditionMet());

            waitForFileInDir(downloadDir, tempFile.getName());
            assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
        } finally {
            seeder.stop(true);
            leech.stop(true);
        }
    }

    private Set<String> listFileNames(File downloadDir) {
        if (downloadDir == null) return Collections.emptySet();
        Set<String> names = new HashSet<String>();
        File[] files = downloadDir.listFiles();
        if (files == null) return Collections.emptySet();
        for (File f : files) {
            names.add(f.getName());
        }
        return names;
    }


    private void waitForSeeder(final byte[] torrentHash) {
        new WaitFor() {
            @Override
            protected boolean condition() {
                for (TrackedTorrent tt : TrackerTest.this.tracker.getTrackedTorrents()) {
                    if (tt.seeders() == 1 && tt.getHexInfoHash().equals(Torrent.byteArrayToHexString(torrentHash)))
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

    private void waitForFileInDir(final File downloadDir, final String fileName, long timeout, long pollInterval) {
        new WaitFor(timeout, pollInterval) {
            @Override
            protected boolean condition() {
                return new File(downloadDir, fileName).isFile();
            }
        };

        assertTrue(new File(downloadDir, fileName).isFile());
    }
    private void waitForFileInDir(final File downloadDir, final String fileName) {
        waitForFileInDir(downloadDir, fileName, WaitFor.DEFAULT_TIMEOUT, WaitFor.DEFAULT_POLL_INTERVAL);
    }

    private TrackedTorrent loadTorrent(String name) throws IOException, NoSuchAlgorithmException {
        return new TrackedTorrent(Torrent.load(new File(TEST_RESOURCES + "/torrents", name), true));
    }


    @AfterMethod
    protected void tearDown() throws Exception {
        stopTracker();
        tempFiles.cleanup();
    }

    private void startTracker() throws IOException {
        this.tracker = new Tracker(new InetSocketAddress(6969));
        this.tracker.start();
    }

    private Client createClient(SharedTorrent torrent) throws IOException, NoSuchAlgorithmException, InterruptedException {
        return new Client(InetAddress.getLocalHost(), torrent);
    }

    private SharedTorrent getCompleteTorrent(String name) throws IOException, NoSuchAlgorithmException {
        File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
        File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
        return SharedTorrent.fromFile(torrentFile, parentFiles);
    }

    private SharedTorrent getIncompleteTorrent(String name, File destDir) throws IOException, NoSuchAlgorithmException {
        File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
        return SharedTorrent.fromFile(torrentFile, destDir);
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
