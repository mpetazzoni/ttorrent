package com.turn.ttorrent.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A shared torrent downloaded from given URI
 *
 * @author pisek
 */
public class UrlSharedTorrent extends SharedTorrent {
	
	private static final Logger logger =
			LoggerFactory.getLogger(UrlSharedTorrent.class);
	
	public UrlSharedTorrent(URI uri, File destDir) throws FileNotFoundException, IOException {
		super(resolveTorrentUrl(uri), destDir);
	}
	
	public static byte[] resolveTorrentUrl(URI uri) throws ClientProtocolException, IOException {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		
		try {
			
			HttpGet get = new HttpGet(uri);
			
			logger.debug("Executing request " + get.getRequestLine());
			HttpResponse response = httpclient.execute(get);
			
			logger.debug("Getting torrent file...");
			HttpEntity entity = response.getEntity();
			
			if (entity != null) {
				InputStream inputStream = entity.getContent();
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				IOUtils.copy(inputStream, outputStream);
				return outputStream.toByteArray();
			}
			
			throw new IOException("Could not resolve torrent file... ["+uri+"]");
			
		} finally {
			httpclient.close();
		}
		
	}
	
}
