package com.turn.ttorrent.common;

import java.io.ByteArrayOutputStream;
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

/**
 * Utility class for keeping common static methods
 */
public final class Utils {
	
	/**
	 * Static class
	 */
	private Utils() {}
	
	/**
	 * Resolves a file from URI and returns its byte array.
	 * 
	 * @param uri
	 * @return
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	public static byte[] resolveUrlFileToByteArray(URI uri) throws ClientProtocolException, IOException {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		
		try {
			
			HttpGet get = new HttpGet(uri);
			HttpResponse response = httpclient.execute(get);
			HttpEntity entity = response.getEntity();
			
			if (entity != null) {
				InputStream inputStream = entity.getContent();
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				IOUtils.copy(inputStream, outputStream);
				return outputStream.toByteArray();
			}
			
			throw new IOException("Could not resolve file... ["+uri+"]");
			
		} finally {
			httpclient.close();
		}
		
	}
	
}
