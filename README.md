Ttorrent, a Java implementation of the BitTorrent protocol
==========================================================

Description
-----------

**Ttorrent** is a pure-Java implementation of the BitTorrent protocol,
providing a BitTorrent tracker, a BitTorrent client and the related Torrent
metainfo files creation and parsing capabilities. It is designed to be embedded
into larger applications, but its components can also be used as standalone
programs.

This fork of ttorrent is a complete structural overhaul for
correctness, improved performance using [Netty](http://netty.io/),
vastly reduced memory consumption, monitoring and metrics, and
optional integration with [Spring Framework](http://spring.io/) and
[Jetty](http://www.eclipse.org/jetty/).

Ttorrent supports the following BEPs (BitTorrent enhancement proposals):

* `BEP#0003`: The BitTorrent protocol specification (complete)
  This is the base official protocol specification, which Ttorrent implements
  fully.
* `BEP#0007`: IPv6 Tracker Extension
* `BEP#0010`: Extension Protocol
* `BEP#0012`: Multi-tracker metadata extension  
  Full support for the `announce-list` meta-info key providing a tiered tracker
  list.
* `BEP#0015`: UDP Tracker Protocol for BitTorrent (partial)
  The UDP tracker protocol is fully supported in the BitTorrent client to make
  announce requests to UDP trackers. UDP tracker support itself is planned.
* `BEP#0020`: Peer ID conventions  
  Ttorrent uses `TO` as the client identification string, and currently uses
  the `-T00042-` client ID prefix.
* `BEP#0023`: Tracker Returns Compact Peer Lists  
  Compact peer lists are supported in both the client and the tracker.
  Currently the tracker only supports sending back compact peer lists
  to an announce request.
* `BEP#0024`: Tracker Returns External IP

In addition, the following extensions are supported:

* Peer Exchange (ut\_pex) with PEX seeding for fast trackerless operation.
* Linux epoll() (See ClientEnvironment#setEventLoopType()).

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

The APIs and implementation were thoroughly overhauled in this fork,
with the objective of sharing a multi-gigabyte file to a thousand
hosts in a matter of seconds.


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
      <groupId>org.anarres.ttorrent</groupId>
      <artifactId>ttorrent-client</groupId>
      <version>1.8.0-SNAPSHOT</version>
    </dependency>
  </dependencies>
```

#### Client code

```java
// First, instantiate the Client object.
Client client = new Client();

// Configure the client as desired.
client.getEnvironment.set*();

// Add one or more torrents.
Torrent torrent = new Torrent(new File("/path/to/your.torrent"))
client.addTorrent(torrent, new File("/path/to/output/directory"));

client.start();

```
At any time you can call `client.stop()` to interrupt the download.
You can also add a
[ClientListener](ttorrent-client/src/main/java/com/turn/ttorrent/client/ClientListener.java)
to wait for a download to complete.

#### Tracker code

For a tracker, there are a number of options:

Standalone:

```java
SimpleTracker tracker = new SimpleTracker(new InetSocketAddress(6969));
tracker.addTorrent(new Torrent("/path/to/file"));
tracker.start();

// You can stop the tracker when you're done with:
tracker.stop();
```

Servlets/Jetty: See [TrackerServlet](ttorrent-tracker-servlet/src/main/java/com/turn/ttorrent/tracker/servlet/TrackerServlet.java).

Spring Framework: See [TrackerController](ttorrent-tracker-spring/src/main/java/com/turn/ttorrent/tracker/spring/TrackerController.java).

#### JavaDoc API

The [JavaDoc API](http://shevek.github.io/ttorrent/docs/javadoc/)
is available.

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


Caveats
-------

Contributions are welcome in all areas, even more so for these few points
above!
