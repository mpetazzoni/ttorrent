Ttorrent, a Java implementation of the BitTorrent protocol
==========================================================

[![Build Status](https://travis-ci.org/mpetazzoni/ttorrent.png)](https://travis-ci.org/mpetazzoni/ttorrent)

Description
-----------

**Ttorrent** is a pure-Java implementation of the BitTorrent protocol,
providing a BitTorrent tracker, a BitTorrent client and the related Torrent
metainfo files creation and parsing capabilities. It is designed to be embedded
into larger applications, but its components can also be used as standalone
programs.

Ttorrent supports the following BEPs (BitTorrent enhancement proposals):

* `BEP#0003`: The BitTorrent protocol specification  
  This is the base official protocol specification, which Ttorrent implements
  fully.
* `BEP#0012`: Multi-tracker metadata extension  
  Full support for the `announce-list` meta-info key providing a tiered tracker
  list.
* `BEP#0015`: UDP Tracker Protocol for BitTorrent  
  The UDP tracker protocol is fully supported in the BitTorrent client to make
  announce requests to UDP trackers. UDP tracker support itself is planned.
* `BEP#0020`: Peer ID conventions  
  Ttorrent uses `TO` as the client identification string, and currently uses
  the `-T00042-` client ID prefix.
* `BEP#0023`: Tracker Returns Compact Peer Lists  
  Compact peer lists are supported in both the client and the tracker.
  Currently the tracker only supports sending back compact peer lists
  to an announce request.

History
-------

This tool suite was implemented as part of Turn's (http://www.turn.com) release
distribution and deployment system and is used to distribute new build tarballs
to a large number of machines inside a datacenter as efficiently as possible.
At the time this project was started, few Java implementations of the
BitTorrent protocol existed and unfortunately none of them fit our needs:

* Vuze's, which is very hard to extract from their codebase, and thus complex
to re-integrate into another application;
* torrent4j, which is largely incomplete and not usable;
* Snark's, which is old, and unfortunately unstable;
* bitext, which was also unfortunately unstable, and extremely slow.

This implementation aims at providing a down-to-earth, simple to use library.
No fancy protocol extensions are implemented here: just the basics that allows
for the exchange and distribution of files through the BitTorrent protocol.

Although the write performance of the BitTorrent client is currently quite poor
(~10MB/sec/connected peer), it has been measured that the distribution of a
150MB file to thousands of machines across several datacenters took no more
than 30 seconds, with very little network overhead for the initial seeder (only
125% of the original file size uploaded by the initial seeder).


How to use
----------

### As standalone programs

The client, tracker and torrent file manipulation utilities will all present a
usage message on the console when invoked with the ``-h`` command-line flag.

### As a library

To use ``ttorrent`` is a library in your project, all you need is to
declare the dependency on the latest version of ``ttorrent``. For
example, if you use Maven, add the following in your POM's dependencies
section:

```xml
  <dependencies>
    ...
    <dependency>
      <groupId>com.turn</groupId>
      <artifactId>ttorrent-core</artifactId>
      <version>1.5</version>
    </dependency>
  </dependencies>
```

If you use Gradle, you'll need a line that looks like this instead:

```
compile 'com.turn:ttorrent-core:1.5'
```

*Thanks to Anatoli Vladev for the code examples in #16.*

#### Client code

```java
// First, instantiate the Client object.
Client client = new Client(
  // This is the interface the client will listen on (you might need something
  // else than localhost here).
  InetAddress.getLocalHost(),

  // Load the torrent from the torrent file and use the given
  // output directory. Partials downloads are automatically recovered.
  SharedTorrent.fromFile(
    new File("/path/to/your.torrent"),
    new File("/path/to/output/directory")));

// You can optionally set download/upload rate limits
// in kB/second. Setting a limit to 0.0 disables rate
// limits.
client.setMaxDownloadRate(50.0);
client.setMaxUploadRate(50.0);

// At this point, can you either call download() to download the torrent and
// stop immediately after...
client.download();

// Or call client.share(...) with a seed time in seconds:
// client.share(3600);
// Which would seed the torrent for an hour after the download is complete.

// Downloading and seeding is done in background threads.
// To wait for this process to finish, call:
client.waitForCompletion();

// At any time you can call client.stop() to interrupt the download.
```

#### Tracker code

```java
// First, instantiate a Tracker object with the port you want it to listen on.
// The default tracker port recommended by the BitTorrent protocol is 6969.
Tracker tracker = new Tracker(new InetSocketAddress(6969));

// Then, for each torrent you wish to announce on this tracker, simply created
// a TrackedTorrent object and pass it to the tracker.announce() method:
FilenameFilter filter = new FilenameFilter() {
  @Override
  public boolean accept(File dir, String name) {
    return name.endsWith(".torrent");
  }
};

for (File f : new File("/path/to/torrent/files").listFiles(filter)) {
  tracker.announce(TrackedTorrent.load(f));
}

// Once done, you just have to start the tracker's main operation loop:
tracker.start();

// You can stop the tracker when you're done with:
tracker.stop();
```

### Track download progress

You can track the progress of the download and the state of the torrent
by registering an `Observer` on your `Client` instance. The observer is
updated every time a piece of the download completes:

```java
client.addObserver(new Observer() {
  @Override
  public void update(Observable observable, Object data) {
    Client client = (Client) observable;
    float progress = client.getTorrent().getCompletion();
    // Do something with progress.
  }
});
```

License
-------

This BitTorrent library is distributed under the terms of the Apache Software
License version 2.0. See COPYING file for more details.


Authors and contributors
------------------------

* Maxime Petazzoni <<maxime.petazzoni@bulix.org>> (Software Engineer at SignalFuse, Inc)  
  Original author, main developer and maintainer
* David Giffin <<david@etsy.com>>  
  Contributed parallel hashing and multi-file torrent support.
* Thomas Zink <<thomas.zink@uni-konstanz.de>>  
  Fixed a piece length computation issue when the total torrent size is an
  exact multiple of the piece size.
* Johan Parent <<parent\_johan@yahoo.com>>  
  Fixed a bug in unfresh peer collection and issues on download completion on
  Windows platforms.
* Dmitriy Dumanskiy  
  Contributed the switch from Ant to Maven.
* Alexey Ptashniy  
  Fixed an integer overflow in the calculation of a torrent's full size.

And many other helpful contributors on GitHub! Thanks to all of you.

Caveats
-------

* Client write performance is a bit poor, mainly due to a (too?) simple piece
  caching algorithm.

Contributions are welcome in all areas, even more so for these few points
above!
