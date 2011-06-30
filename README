A Java implementation of the BitTorrent protocol
================================================

This is a pure-Java implementation of the BitTorrent protocol, providing a
BitTorrent tracker, a BitTorrent client and the related Torrent metainfo files
creation and parsing capabilities. It is designed to be embedded into larger
applications.

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


License
-------

This BitTorrent library is distributed under the terms of the Apache Software
License version 2.0. See COPYING file for more details.


Authors
-------

* Maxime Petazzoni <mpetazzoni@turn.com> (Platform Engineer at Turn, Inc)


Caveats
-------

* This implementation currently only supports single-file torrents.
* Client write performance is a bit poor, mainly due to a (too?) simple piece
caching algorithm.
* Start of transfer can take a few seconds. This is mostly due to the protocol
itself, but I wonder if something could be done to improve this.

Contributions are welcome in all areas, even more so for these few points
above!
