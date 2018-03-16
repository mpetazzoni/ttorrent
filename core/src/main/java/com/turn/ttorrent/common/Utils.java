/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
 * 
 * @author pisek
 * @since 24.11.2015
 */
public final class Utils {
  
  private final static char[] HEX_SYMBOLS = "0123456789ABCDEF".toCharArray();

	
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
    
    /**
     * Convert a byte string to a string containing the hexadecimal
     * representation of the original data.
     *
     * @param bytes The byte array to convert.
     * @see <a href="http://stackoverflow.com/questions/332079">http://stackoverflow.com/questions/332079</a>
     */
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_SYMBOLS[v >>> 4];
            hexChars[j * 2 + 1] = HEX_SYMBOLS[v & 0x0F];
        }
        return new String(hexChars);
    }
		
	}
}
