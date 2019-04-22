/*
 * Copyright 2000-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.ttorrent.common.creation;

import com.turn.ttorrent.Constants;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import com.turn.ttorrent.common.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.*;
import java.util.*;

import static com.turn.ttorrent.common.TorrentMetadataKeys.*;

public class MetadataBuilder {

  private final static Logger logger = TorrentLoggerFactory.getLogger(MetadataBuilder.class);
  private final static String DEFAULT_CREATED_BY = "ttorrent library";

  private final TimeService timeService;

  //root dictionary
  @NotNull
  private String announce = "";
  @NotNull
  private List<List<String>> announceList = new ArrayList<List<String>>();
  private long creationDate = -1;
  @NotNull
  private String comment = "";
  @NotNull
  private String createdBy = DEFAULT_CREATED_BY;
  @NotNull
  private List<String> webSeedUrlList = new ArrayList<String>();
  //end root dictionary

  //info dictionary
  private int pieceLength = 512 * 1024;//512kb by default
  private boolean isPrivate = false;
  @NotNull
  private List<Source> sources = new ArrayList<Source>();
  @NotNull
  private String directoryName = "";
  //end info dictionary

  //fields which store some internal information
  @NotNull
  private PiecesHashesCalculator piecesHashesCalculator = new SingleThreadHashesCalculator();
  //end

  public MetadataBuilder() {
    this(new SystemTimeService());
  }

  public MetadataBuilder(TimeService timeService) {
    this.timeService = timeService;
  }

  /**
   * set main announce tracker URL if you use single tracker.
   * In case with many trackers use {@link #addTracker(String)}
   * and {@link #newTier()}. Then as main announce will be selected first tracker.
   * You can specify main announce using this method for override this behaviour
   * Torrent clients which support BEP12 extension will ignore main announce.
   *
   * @param announce announce URL for the tracker
   */
  public MetadataBuilder setTracker(String announce) {
    this.announce = announce;
    return this;
  }


  /**
   * Multi-tracker Metadata Extension. Add new tracker URL to current tier.
   * This method will create first tier automatically if it doesn't exist
   * You can find more information about this extension in documentation
   * <a href="http://bittorrent.org/beps/bep_0012.html">http://bittorrent.org/beps/bep_0012.html</a>
   *
   * @param url tracker url
   */
  public MetadataBuilder addTracker(String url) {
    initFirstTier();
    announceList.get(announceList.size() - 1).add(url);
    return this;
  }

  /**
   * Multi-tracker Metadata Extension. Add all trackers to current tier.
   * This method will create first tier automatically if it doesn't exist
   * You can find more information about this extension in documentation
   * <a href="http://bittorrent.org/beps/bep_0012.html">http://bittorrent.org/beps/bep_0012.html</a>
   *
   * @param trackers collections of trackers URLs
   */
  public MetadataBuilder addTrackers(Collection<String> trackers) {
    initFirstTier();
    announceList.get(announceList.size() - 1).addAll(trackers);
    return this;
  }

  /**
   * Multi-tracker Metadata Extension. Create new tier for adding tracker using {@link #addTracker(String)} method
   * If you don't add at least one tracker on the tier this tier will be removed in building metadata
   * You can find more information about this extension in documentation
   * <a href="http://bittorrent.org/beps/bep_0012.html">http://bittorrent.org/beps/bep_0012.html</a>
   */
  public MetadataBuilder newTier() {
    announceList.add(new ArrayList<String>());
    return this;
  }

  /**
   * Web Seeding Metadata.
   * Web seeding url as defined by <a href='http://bittorrent.org/beps/bep_0019.html'>bep 0019</a>
   * @param url URL to add for web seeding
   */
  public MetadataBuilder addWebSeedUrl(String url)  {
    webSeedUrlList.add(url);
    return this;
  }

  /**
   * Set the creation time of the torrent in standard UNIX epoch format.
   *
   * @param creationTime the seconds since January 1, 1970, 00:00:00 UTC.
   */
  public MetadataBuilder setCreationTime(int creationTime) {
    this.creationDate = creationTime;
    return this;
  }

  /**
   * Set free-form textual comment of the author
   */
  public MetadataBuilder setComment(String comment) {
    this.comment = comment;
    return this;
  }

  /**
   * Set program name which is used for creating torrent file.
   */
  public MetadataBuilder setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
    return this;
  }

  /**
   * Set {@link PiecesHashesCalculator} instance for calculating hashes. In rare cases user's
   * implementation can be used for increasing hashing performance
   */
  public MetadataBuilder setPiecesHashesCalculator(@NotNull PiecesHashesCalculator piecesHashesCalculator) {
    this.piecesHashesCalculator = piecesHashesCalculator;
    return this;
  }

  /**
   * Set length int bytes of one piece. By default is used 512KB.
   * Larger piece size reduces size of .torrent file but cause inefficiency
   * (torrent-client need to download full piece from peer for validating)
   * and too-small piece sizes cause large .torrent metadata file.
   * Recommended size is between 256KB and 1MB.
   */
  public MetadataBuilder setPieceLength(int pieceLength) {
    this.pieceLength = pieceLength;
    return this;
  }

  /**
   * Set the name of the directory in which to store all the files.
   * If {@link #directoryName} isn't empty then multi-file torrent will be created, otherwise single-file
   */
  public MetadataBuilder setDirectoryName(@NotNull String directoryName) {
    this.directoryName = directoryName;
    return this;
  }

  /**
   * add custom source in torrent with custom path. Path can be separated with any slash.
   *
   * @param closeAfterBuild if true then source stream will be closed after {@link #build()} invocation
   */
  public MetadataBuilder addDataSource(@NotNull InputStream dataSource, String path, boolean closeAfterBuild) {
    sources.add(new Source(dataSource, path, closeAfterBuild));
    return this;
  }

  /**
   * add custom source in torrent with custom path. Path can be separated with any slash.
   */
  public MetadataBuilder addDataSource(@NotNull InputStream dataSource, String path) {
    addDataSource(dataSource, path, true);
    return this;
  }

  /**
   * add specified file in torrent with custom path. The file will be stored in .torrent
   * by specified path. Path can be separated with any slash. In case of single-file torrent
   * this path will be used as name of source file
   */
  public MetadataBuilder addFile(@NotNull File source, @NotNull String path) {
    if (!source.isFile()) {
      throw new IllegalArgumentException(source + " is not exist");
    }
    sources.add(new Source(source, path));
    return this;
  }

  /**
   * add specified file in torrent. In case of multi-torrent this file will be downloaded to
   * {@link #directoryName}. In single-file torrent this file will be downloaded in download folder
   */
  public MetadataBuilder addFile(@NotNull File source) {
    sources.add(new Source(source));
    return this;
  }

  /**
   * marks torrent as private
   *
   * @see <a href="http://bittorrent.org/beps/bep_0027.html">http://bittorrent.org/beps/bep_0027.html</a>
   */
  public void doPrivate() {
    isPrivate = true;
  }

  /**
   * marks torrent as public
   *
   * @see <a href="http://bittorrent.org/beps/bep_0027.html">http://bittorrent.org/beps/bep_0027.html</a>
   */
  public void doPublic() {
    isPrivate = false;
  }

  /**
   * @return new {@link TorrentMetadata} instance with builder's fields
   * @throws IOException           if IO error occurs on reading from source streams and files
   * @throws IllegalStateException if builder's state is incorrect (e.g. missing required fields)
   */
  public TorrentMetadata build() throws IOException {
    return new TorrentParser().parse(buildBinary());
  }

  /**
   * @return binary representation of metadata
   * @throws IOException           if IO error occurs on reading from source streams and files
   * @throws IllegalStateException if builder's state is incorrect (e.g. missing required fields)
   */
  public byte[] buildBinary() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BEncoder.bencode(buildBEP(), baos);
    return baos.toByteArray();
  }

  /**
   * @return BEP-encoded dictionary of metadata
   * @throws IOException           if IO error occurs on reading from source streams and files
   * @throws IllegalStateException if builder's state is incorrect (e.g. missing required fields)
   */
  public BEValue buildBEP() throws IOException {
    return buildAndCloseResources();
  }

  private BEValue buildAndCloseResources() throws IOException {
    try {
      return doBuild();
    } finally {
      closeAllSources();
    }
  }

  private BEValue doBuild() throws IOException {
    dropEmptyTiersFromAnnounce();

    if (announce.isEmpty() && !announceList.isEmpty()) {
        announce = announceList.get(0).get(0);
    }
    if (sources.size() == 0) {
      throw new IllegalStateException("Unable to create metadata without sources. Use addSource() method for adding sources");
    }
    final boolean isSingleMode = sources.size() == 1 && directoryName.isEmpty();
    final String name;
    if (!directoryName.isEmpty()) {
      name = directoryName;
    } else {
      if (isSingleMode) {
        name = sources.get(0).getPath();
      } else {
        throw new IllegalStateException("Missing required field 'name'. Use setDirectoryName() method for specifying name of torrent");
      }
    }

    Map<String, BEValue> torrent = new HashMap<String, BEValue>();
    if (!announce.isEmpty()) torrent.put(ANNOUNCE, new BEValue(announce));
    if (!announceList.isEmpty()) torrent.put(ANNOUNCE_LIST, wrapAnnounceList());
    if (creationDate > 0) {
      torrent.put(CREATION_DATE_SEC, new BEValue(creationDate));
    }

    if (!comment.isEmpty()) torrent.put(COMMENT, new BEValue(comment));
    if (!createdBy.isEmpty()) torrent.put(CREATED_BY, new BEValue(createdBy));
    if (!webSeedUrlList.isEmpty()) torrent.put(URL_LIST, wrapStringList(webSeedUrlList));

    HashingResult hashingResult = piecesHashesCalculator.calculateHashes(mapSources(sources), pieceLength);

    Map<String, BEValue> info = new HashMap<String, BEValue>();
    info.put(PIECE_LENGTH, new BEValue(pieceLength));
    info.put(PIECES, concatHashes(hashingResult.getHashes()));
    info.put(PRIVATE, new BEValue(isPrivate ? 1 : 0));
    info.put(NAME, new BEValue(name));
    if (isSingleMode) {
      Long sourceSize = hashingResult.getSourceSizes().get(0);
      info.put(FILE_LENGTH, new BEValue(sourceSize));
    } else {
      List<BEValue> files = getFilesList(hashingResult);
      info.put(FILES, new BEValue(files));
    }
    torrent.put(INFO_TABLE, new BEValue(info));

    return new BEValue(torrent);
  }

  private List<BEValue> getFilesList(HashingResult hashingResult) throws UnsupportedEncodingException {
    ArrayList<BEValue> result = new ArrayList<BEValue>();
    for (int i = 0; i < sources.size(); i++) {
      Map<String, BEValue> file = new HashMap<String, BEValue>();
      Long sourceSize = hashingResult.getSourceSizes().get(i);
      Source source = sources.get(i);
      List<BEValue> filePath = new ArrayList<BEValue>();
      for (String path : source.getPath().replace("\\", "/").split("/")) {
        filePath.add(new BEValue(path));
      }
      file.put(FILE_PATH, new BEValue(filePath));
      file.put(FILE_LENGTH, new BEValue(sourceSize));
      result.add(new BEValue(file));
    }
    return result;
  }

  private BEValue concatHashes(List<byte[]> hashes) throws UnsupportedEncodingException {
    StringBuilder sb = new StringBuilder();
    for (byte[] hash : hashes) {
      sb.append(new String(hash, Constants.BYTE_ENCODING));
    }
    return new BEValue(sb.toString(), Constants.BYTE_ENCODING);
  }

  private List<DataSourceHolder> mapSources(List<Source> sources) {
    List<DataSourceHolder> result = new ArrayList<DataSourceHolder>();
    for (Source source : sources) {
      result.add(source.getSourceHolder());
    }
    return result;
  }

  private BEValue wrapStringList(List<String> lst) throws UnsupportedEncodingException {
    List<BEValue> result = new LinkedList<BEValue>();
    for(String s : lst) {
      result.add(new BEValue(s));
    }
    return new BEValue(result);
  }

  private BEValue wrapAnnounceList() throws UnsupportedEncodingException {
    List<BEValue> result = new LinkedList<BEValue>();
    for (List<String> tier : announceList) {
      result.add(wrapStringList(tier));
    }
    return new BEValue(result);
  }

  private void dropEmptyTiersFromAnnounce() {
    Iterator<List<String>> iterator = announceList.iterator();
    while (iterator.hasNext()) {
      List<String> tier = iterator.next();
      if (tier.isEmpty()) {
        iterator.remove();
      }
    }
  }

  private void closeAllSources() {
    for (Source source : sources) {
      DataSourceHolder sourceHolder = source.getSourceHolder();
      try {
        sourceHolder.close();
      } catch (Throwable e) {
        logger.error("Error in closing data source " + sourceHolder, e);
      }
    }
  }

  private void initFirstTier() {
    if (announceList.isEmpty()) {
      newTier();
    }
  }

}
