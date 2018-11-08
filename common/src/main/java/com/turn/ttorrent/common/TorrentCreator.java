package com.turn.ttorrent.common;

import com.turn.ttorrent.Constants;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import com.turn.ttorrent.common.creation.MetadataBuilder;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.turn.ttorrent.common.TorrentMetadataKeys.*;

/**
 * Old API for creating .torrent files, use {@link MetadataBuilder}
 * @deprecated
 */
@Deprecated
public class TorrentCreator {

  private final static Logger logger = TorrentLoggerFactory.getLogger(TorrentCreator.class);

  /**
   * Torrent file piece length (in bytes), we use 512 kB.
   */
  public static final int DEFAULT_PIECE_LENGTH = 512 * 1024;
  private static final int HASHING_TIMEOUT_SEC = 15;
  public static int HASHING_THREADS_COUNT = Runtime.getRuntime().availableProcessors();

  static {
    String threads = System.getenv("TTORRENT_HASHING_THREADS");

    if (threads != null) {
      try {
        int count = Integer.parseInt(threads);
        if (count > 0) {
          TorrentCreator.HASHING_THREADS_COUNT = count;
        }
      } catch (NumberFormatException nfe) {
        // Pass
      }
    }
  }

  private static final ExecutorService HASHING_EXECUTOR = Executors.newFixedThreadPool(HASHING_THREADS_COUNT, new ThreadFactory() {
    @Override
    public Thread newThread(@NotNull final Runnable r) {
      final Thread thread = new Thread(r);
      thread.setDaemon(true);
      return thread;
    }
  });

  /**
   * Create a {@link TorrentMetadata} object for a file.
   *
   * <p>
   * Hash the given file to create the {@link TorrentMetadata} object representing
   * the Torrent meta info about this file, needed for announcing and/or
   * sharing said file.
   * </p>
   *
   * @param source    The file to use in the torrent.
   * @param announce  The announce URI that will be used for this torrent.
   * @param createdBy The creator's name, or any string identifying the
   *                  torrent's creator.
   */
  public static TorrentMetadata create(File source, URI announce, String createdBy)
          throws InterruptedException, IOException {
    return create(source, null, announce, createdBy);
  }

  /**
   * Create a {@link TorrentMetadata} object for a set of files.
   *
   * <p>
   * Hash the given files to create the multi-file {@link TorrentMetadata} object
   * representing the Torrent meta-info about them, needed for announcing
   * and/or sharing these files. Since we created the torrent, we're
   * considering we'll be a full initial seeder for it.
   * </p>
   *
   * @param parent    The parent directory or location of the torrent files,
   *                  also used as the torrent's name.
   * @param files     The files to add into this torrent.
   * @param announce  The announce URI that will be used for this torrent.
   * @param createdBy The creator's name, or any string identifying the
   *                  torrent's creator.
   */
  public static TorrentMetadata create(File parent, List<File> files, URI announce,
                                       String createdBy) throws InterruptedException, IOException {
    return create(parent, files, announce, null, createdBy);
  }

  /**
   * Create a {@link TorrentMetadata} object for a file.
   *
   * <p>
   * Hash the given file to create the {@link TorrentMetadata} object representing
   * the Torrent metainfo about this file, needed for announcing and/or
   * sharing said file.
   * </p>
   *
   * @param source       The file to use in the torrent.
   * @param announceList The announce URIs organized as tiers that will
   *                     be used for this torrent
   * @param createdBy    The creator's name, or any string identifying the
   *                     torrent's creator.
   */
  public static TorrentMetadata create(File source, List<List<URI>> announceList,
                                       String createdBy) throws InterruptedException, IOException {
    return create(source, null, null, announceList, createdBy);
  }

  /**
   * Create a {@link TorrentMetadata} object for a set of files.
   *
   * <p>
   * Hash the given files to create the multi-file {@link TorrentMetadata} object
   * representing the Torrent meta-info about them, needed for announcing
   * and/or sharing these files. Since we created the torrent, we're
   * considering we'll be a full initial seeder for it.
   * </p>
   *
   * @param source       The parent directory or location of the torrent files,
   *                     also used as the torrent's name.
   * @param files        The files to add into this torrent.
   * @param announceList The announce URIs organized as tiers that will
   *                     be used for this torrent
   * @param createdBy    The creator's name, or any string identifying the
   *                     torrent's creator.
   */
  public static TorrentMetadata create(File source, List<File> files,
                                       List<List<URI>> announceList, String createdBy)
          throws InterruptedException, IOException {
    return create(source, files, null, announceList, createdBy);
  }

  /**
   * Helper method to create a {@link TorrentMetadata} object for a set of files.
   *
   * <p>
   * Hash the given files to create the multi-file {@link TorrentMetadata} object
   * representing the Torrent meta-info about them, needed for announcing
   * and/or sharing these files. Since we created the torrent, we're
   * considering we'll be a full initial seeder for it.
   * </p>
   *
   * @param parent       The parent directory or location of the torrent files,
   *                     also used as the torrent's name.
   * @param files        The files to add into this torrent.
   * @param announce     The announce URI that will be used for this torrent.
   * @param announceList The announce URIs organized as tiers that will
   *                     be used for this torrent
   * @param createdBy    The creator's name, or any string identifying the
   *                     torrent's creator.
   */
  public static TorrentMetadata create(File parent, List<File> files, URI announce, List<List<URI>> announceList, String createdBy)
          throws InterruptedException, IOException {
    return create(parent, files, announce, announceList, createdBy, DEFAULT_PIECE_LENGTH);
  }

  public static TorrentMetadata create(File parent, List<File> files, URI announce,
                                       List<List<URI>> announceList, String createdBy, final int pieceSize)
          throws InterruptedException, IOException {
    return create(parent, files, announce, announceList, createdBy, System.currentTimeMillis() / 1000, pieceSize);
  }

  //for tests
  /*package local*/
  static TorrentMetadata create(File parent, List<File> files, URI announce,
                                List<List<URI>> announceList, String createdBy, long creationTimeSecs, final int pieceSize)
          throws InterruptedException, IOException {
    Map<String, BEValue> torrent = new HashMap<String, BEValue>();

    if (announce != null) {
      torrent.put(ANNOUNCE, new BEValue(announce.toString()));
    }
    if (announceList != null) {
      List<BEValue> tiers = new LinkedList<BEValue>();
      for (List<URI> trackers : announceList) {
        List<BEValue> tierInfo = new LinkedList<BEValue>();
        for (URI trackerURI : trackers) {
          tierInfo.add(new BEValue(trackerURI.toString()));
        }
        tiers.add(new BEValue(tierInfo));
      }
      torrent.put(ANNOUNCE_LIST, new BEValue(tiers));
    }
    torrent.put(CREATION_DATE_SEC, new BEValue(creationTimeSecs));
    torrent.put(CREATED_BY, new BEValue(createdBy));

    Map<String, BEValue> info = new TreeMap<String, BEValue>();
    info.put(NAME, new BEValue(parent.getName()));
    info.put(PIECE_LENGTH, new BEValue(pieceSize));

    if (files == null || files.isEmpty()) {
      info.put(FILE_LENGTH, new BEValue(parent.length()));
      info.put(PIECES, new BEValue(hashFile(parent, pieceSize),
              Constants.BYTE_ENCODING));
    } else {
      List<BEValue> fileInfo = new LinkedList<BEValue>();
      for (File file : files) {
        Map<String, BEValue> fileMap = new HashMap<String, BEValue>();
        fileMap.put(FILE_LENGTH, new BEValue(file.length()));

        LinkedList<BEValue> filePath = new LinkedList<BEValue>();
        while (file != null) {
          if (file.equals(parent)) {
            break;
          }

          filePath.addFirst(new BEValue(file.getName()));
          file = file.getParentFile();
        }

        fileMap.put(FILE_PATH, new BEValue(filePath));
        fileInfo.add(new BEValue(fileMap));
      }
      info.put(FILES, new BEValue(fileInfo));
      info.put(PIECES, new BEValue(hashFiles(files, pieceSize),
              Constants.BYTE_ENCODING));
    }
    torrent.put(INFO_TABLE, new BEValue(info));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BEncoder.bencode(new BEValue(torrent), baos);
    return new TorrentParser().parse(baos.toByteArray());
  }

  /**
   * Return the concatenation of the SHA-1 hashes of a file's pieces.
   *
   * <p>
   * Hashes the given file piece by piece using the default Torrent piece
   * length (see {@link #DEFAULT_PIECE_LENGTH}) and returns the concatenation of
   * these hashes, as a string.
   * </p>
   *
   * <p>
   * This is used for creating Torrent meta-info structures from a file.
   * </p>
   *
   * @param file The file to hash.
   */
  private static String hashFile(final File file, final int pieceSize)
          throws InterruptedException, IOException {
    return hashFiles(Collections.singletonList(file), pieceSize);
  }

  private static String hashFiles(final List<File> files, final int pieceSize)
          throws InterruptedException, IOException {
    if (files.size() == 0) {
      return "";
    }
    List<Future<String>> results = new LinkedList<Future<String>>();
    long length = 0L;

    final ByteBuffer buffer = ByteBuffer.allocate(pieceSize);


    final AtomicInteger threadIdx = new AtomicInteger(0);
    final String firstFileName = files.get(0).getName();

    StringBuilder hashes = new StringBuilder();

    long start = System.nanoTime();
    for (File file : files) {
      logger.debug("Analyzing local data for {} with {} threads...",
              file.getName(), HASHING_THREADS_COUNT);

      length += file.length();

      FileInputStream fis = new FileInputStream(file);
      FileChannel channel = fis.getChannel();

      try {
        while (channel.read(buffer) > 0) {
          if (buffer.remaining() == 0) {
            buffer.clear();
            final ByteBuffer data = prepareDataFromBuffer(buffer);

            results.add(HASHING_EXECUTOR.submit(new Callable<String>() {
              @Override
              public String call() throws Exception {
                Thread.currentThread().setName(String.format("%s hasher #%d", firstFileName, threadIdx.incrementAndGet()));
                return new CallableChunkHasher(data).call();
              }
            }));
          }

          if (results.size() >= HASHING_THREADS_COUNT) {
            // process hashers, otherwise they will spend too much memory
            waitForHashesToCalculate(results, hashes);
            results.clear();
          }
        }
      } finally {
        channel.close();
        fis.close();
      }
    }

    // Hash the last bit, if any
    if (buffer.position() > 0) {
      buffer.limit(buffer.position());
      buffer.position(0);
      final ByteBuffer data = prepareDataFromBuffer(buffer);
      results.add(HASHING_EXECUTOR.submit(new CallableChunkHasher(data)));
    }
    // here we have only a few hashes to wait for calculation
    waitForHashesToCalculate(results, hashes);

    long elapsed = System.nanoTime() - start;

    int expectedPieces = (int) (Math.ceil(
            (double) length / pieceSize));
    logger.debug("Hashed {} file(s) ({} bytes) in {} pieces ({} expected) in {}ms.",
            new Object[]{
                    files.size(),
                    length,
                    results.size(),
                    expectedPieces,
                    String.format("%.1f", elapsed / 1e6),
            });

    return hashes.toString();
  }

  private static ByteBuffer prepareDataFromBuffer(ByteBuffer buffer) {
    final ByteBuffer data = ByteBuffer.allocate(buffer.remaining());
    buffer.mark();
    data.put(buffer);
    data.clear();
    buffer.reset();
    return data;
  }

  private static void waitForHashesToCalculate(List<Future<String>> results, StringBuilder hashes) throws InterruptedException, IOException {
    try {
      for (Future<String> chunk : results) {
        hashes.append(chunk.get(HASHING_TIMEOUT_SEC, TimeUnit.SECONDS));
      }
    } catch (ExecutionException ee) {
      throw new IOException("Error while hashing the torrent data!", ee);
    } catch (TimeoutException e) {
      throw new RuntimeException(String.format("very slow hashing: took more than %d seconds to calculate several pieces. Cancelling", HASHING_TIMEOUT_SEC));
    }
  }

  /**
   * A {@link Callable} to hash a data chunk.
   *
   * @author mpetazzoni
   */
  private static class CallableChunkHasher implements Callable<String> {

    private final ByteBuffer data;

    CallableChunkHasher(final ByteBuffer data) {
      this.data = data;
    }

    @Override
    public String call() throws UnsupportedEncodingException {
      byte[] sha1Hash = TorrentUtils.calculateSha1Hash(this.data.array());
      return new String(sha1Hash, Constants.BYTE_ENCODING);
    }
  }
}
