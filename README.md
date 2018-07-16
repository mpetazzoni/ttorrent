[![JetBrains team project](http://jb.gg/badges/team.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

Ttorrent, a Java implementation of the BitTorrent protocol
==========================================================

#### Note
It's Ttorrent library version 2.0 which has
a lot of improvements and may not be compatible with previous version
(stored in [v1.6 branch](https://github.com/mpetazzoni/ttorrent/tree/v1.6))

See [this issue](https://github.com/mpetazzoni/ttorrent/issues/212) for details 

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

How to use
----------

### As a library

#### Client code

```java
// First, instantiate the Client object.
SimpleClient client = new SimpleClient();

// This is the interface the client will listen on (you might need something
// else than localhost here because other peers cannot connect to localhost).
InetAddress address = InetAddress.getLocalHost();

//Start download. Thread is blocked here
try {
  client.downloadTorrent("/path/to/filed.torrent",
          "/path/to/output/directory",
          address);
  //download finished
} catch (Exception e) {
  //download failed, see exception for details
  e.printStackTrace();
}
//If you don't want to seed the torrent you can stop client
client.stop();
```

#### Tracker code

```java
// First, instantiate a Tracker object with the port you want it to listen on.
// The default tracker port recommended by the BitTorrent protocol is 6969.
Tracker tracker = new Tracker(6969);

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

//Also you can enable accepting foreign torrents.
//if tracker accepts request for unknown torrent it starts tracking the torrent automatically
tracker.setAcceptForeignTorrents(true);

// Once done, you just have to start the tracker's main operation loop:
tracker.start(true);

// You can stop the tracker when you're done with:
tracker.stop();
```

License
-------

This BitTorrent library is distributed under the terms of the Apache Software
License version 2.0. See COPYING file for more details.


Authors and contributors
------------------------

* Maxime Petazzoni <<mpetazzoni@turn.com>> (Platform Engineer at Turn, Inc)  
  Original author, main developer and maintainer
* David Giffin <<david@etsy.com>>  
  Contributed parallel hashing and multi-file torrent support.
* Thomas Zink <<thomas.zink@uni-konstanz.de>>  
  Fixed a piece length computation issue when the total torrent size is an
  exact multiple of the piece size.
* Johan Parent <<parent_johan@yahoo.com>>  
  Fixed a bug in unfresh peer collection and issues on download completion on
  Windows platforms.
* Dmitriy Dumanskiy  
  Contributed the switch from Ant to Maven.
* Alexey Ptashniy  
  Fixed an integer overflow in the calculation of a torrent's full size.


Caveats
-------

* Client write performance is a bit poor, mainly due to a (too?) simple piece
  caching algorithm.

Contributions are welcome in all areas, even more so for these few points
above!
