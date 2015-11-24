package com.turn.ttorrent.client;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;

public class TorrentFactory {
	
	private File inputFile;
	private File outputDir;
	private URI networkTorrentUri;
	private URI magnetUri;
	
	private TorrentFactory() {}
	
	public SharedTorrent create() throws ParameterException, FileNotFoundException, IOException {
		
		if (outputDir == null) {
			throw new ParameterException("Parameter "+outputDir+" cannot be null...");
		}
		
		if (magnetUri != null) {
			return null;	//TODO 
		} else if (networkTorrentUri != null) {
			return new UrlSharedTorrent(networkTorrentUri, outputDir);
		} else if (inputFile != null) {
			return new SharedTorrent(inputFile, outputDir);
		} else {
			throw new ParameterException("You must provide at least one param out of [magnetUri, networkTorrentUri, inputFile]");
		}
		
	}
	
	public TorrentFactory begin() {
		return new TorrentFactory();
	}

	public TorrentFactory inputFile(File inputFile) {
		this.inputFile = inputFile;
		return this;
	}

	public TorrentFactory outputDir(File outputDir) {
		this.outputDir = outputDir;
		return this;
	}

	public TorrentFactory networkTorrentUri(URI networkTorrentUri) {
		this.networkTorrentUri = networkTorrentUri;
		return this;
	}

	public TorrentFactory magnetUri(URI magnetUri) {
		this.magnetUri = magnetUri;
		return this;
	}	
	
}
