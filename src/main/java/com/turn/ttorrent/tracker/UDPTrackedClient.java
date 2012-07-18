package com.turn.ttorrent.tracker;

import java.net.InetSocketAddress;
import java.util.Calendar;
import java.util.Date;

/**
 * Represent an UDP tracker client.
 * 
 * We need to keep some informations about an UDP client such
 * as the connectionId or the expiration date for some time.
 * (2 minutes)
 * 
 * @author sroze
 */
public class UDPTrackedClient 
{
	private InetSocketAddress address;
	private Long connectionId;
	private Date expirationDate = null;
	
	/**
	 * 
	 * @param address
	 * @param connectionId
	 */
	public UDPTrackedClient(InetSocketAddress address, Long connectionId) {
		this.address = address;
		this.connectionId = connectionId;
	}
	
	/**
	 * Return the InetSocketAddress of client.
	 * 
	 * @return
	 */
	public InetSocketAddress getAddress ()
	{
		return this.address;
	}
	
	/**
	 * Return the connectionId
	 * 
	 * @return
	 */
	public Long getConnectionId ()
	{
		return this.connectionId;
	}
	
	/**
	 * Change the expiration date of a tracked client.
	 * 
	 * @param measure
	 * @param value
	 */
	public void expireIn (int measure, int value)
	{
		Calendar now = Calendar.getInstance();
		now.add(measure, value);
		this.expirationDate = now.getTime();
	}

	/**
	 * Return true if the client is expired.
	 * 
	 * @return boolean
	 */
	public boolean isExpired ()
	{
		return (new Date().after(this.expirationDate));
	}
}
