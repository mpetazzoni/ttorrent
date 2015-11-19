package com.turn.ttorrent.bcodec;

import java.nio.charset.Charset;

/**
 * String class which maps given byte array into a String<br>
 * Using pure {@link String} class does not work properly as it does not accept all negative bytes (like 91-94 etc).
 * 
 * @author pisek
 */
public class BEString implements Cloneable, Comparable<BEString> {
	
	private byte[] bytes;
	private Charset charset;
	
	public BEString(byte[] bytes, Charset charset) {
		this.bytes = bytes;
		this.charset = charset;
	}
	
	public BEString(byte[] bytes) {
		this(bytes, BDecoder.DEFAULT_CHARSET);
	}
	
	public static BEString fromString(String s) {
		
		return new BEString(s.getBytes(BDecoder.DEFAULT_CHARSET), BDecoder.DEFAULT_CHARSET);
	}
	
	public byte[] getBytes() {
		return bytes;
	}

	public Charset getCharset() {
		return charset;
	}
	
	/**
	 * Generates {@link String} object from given byte array and {@link Charset}
	 */
	@Override
	public String toString() {
		return new String(bytes, charset);
	}
	
	/**
	 * Generates a proper info_hash string from bytes
	 * @return
	 */
	public String toStringInfoHash() {
		return BDecoder.getStringInfoHash(bytes);
	}

	/**
	 * @autogenerated by CodeHaggis (http://sourceforge.net/projects/haggis)
	 * @overwrite hashCode()
	 * @return int the Objects hashcode.
	 */
	@Override
	public int hashCode() {// NOPMD
		int hashCode = 1;
		for (int i0 = 0; bytes != null && i0 < bytes.length; i0++) {
			hashCode = 31 * hashCode + (int) bytes[i0];
		}
		hashCode = 31 * hashCode + (charset == null ? 0 : charset.hashCode());
		return hashCode;
	}

	/**
	 * @autogenerated by CodeHaggis (http://sourceforge.net/projects/haggis)
	 * @overwrite equals()
	 * @return boolean returns a boolean value, which calculates, if the objects are equal.
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null) {
			return false;
		}
		if (o.getClass() != getClass()) {
			return false;
		}
		BEString castedObj = (BEString) o;
		return (java.util.Arrays.equals(this.bytes, castedObj.bytes) && (this.charset == null ? castedObj.charset == null : this.charset.equals(castedObj.charset)));
	}

	/**
	 * @autogenerated by CodeHaggis (http://sourceforge.net/projects/haggis)
	 * compareTo()
	 * @param BEString comparator
	 * @return int
	 */
	@Override
	public int compareTo(BEString comparator) {
		if (this.bytes != null && comparator.bytes != null) {
			if (this.bytes.length > comparator.bytes.length)
				return 1;
			if (this.bytes.length < comparator.bytes.length)
				return -1;
			for (int i0 = 0; i0 < this.bytes.length; i0++) {
				if (this.bytes[i0] > comparator.bytes[i0])
					return 1;
				if (this.bytes[i0] < comparator.bytes[i0])
					return -1;
			}
		} else {
			if (this.bytes != null)
				return 1;
			if (comparator.bytes != null)
				return -1;
		}
		this.charset.compareTo(comparator.charset);
		return 0;
	}

	/**
	 * @autogenerated by CodeHaggis (http://sourceforge.net/projects/haggis)
	 * clone
	 * @return Object
	 */
	@Override
	public BEString clone() {
		BEString obj = null;
		/* Clone not supported! A super.clone() method or default constructor needed.*/
		try {
			throw new CloneNotSupportedException();
		} catch (CloneNotSupportedException e) {
			e.printStackTrace();
		}
		return obj;
	}

}
