/*
  Copyright (C) 2016 Philipp Henkel
  <p>
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  <p>
  http://www.apache.org/licenses/LICENSE-2.0
  <p>
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package com.turn.ttorrent.common;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class TorrentUtilsTest {

  @Test(expectedExceptions = NullPointerException.class)
  public void testBytesToHexWithNull() {
    //noinspection ResultOfMethodCallIgnored,ConstantConditions
    TorrentUtils.byteArrayToHexString(null);
  }

  @Test
  public void testBytesToHexWithEmptyByteArray() {
    assertEquals("", TorrentUtils.byteArrayToHexString(new byte[0]));
  }

  @Test
  public void testBytesToHexWithSingleByte() {
    assertEquals("BC", TorrentUtils.byteArrayToHexString(new byte[]{
            (byte) 0xBC
    }));
  }

  @Test
  public void testBytesToHexWithZeroByte() {
    assertEquals("00", TorrentUtils.byteArrayToHexString(new byte[1]));
  }

  @Test
  public void testBytesToHexWithLeadingZero() {
    assertEquals("0053FF", TorrentUtils.byteArrayToHexString(new byte[]{
            (byte) 0x00, (byte) 0x53, (byte) 0xFF
    }));
  }

  @Test
  public void testBytesToHexTrailingZero() {
    assertEquals("AA004500", TorrentUtils.byteArrayToHexString(new byte[]{
            (byte) 0xAA, (byte) 0x00, (byte) 0x45, (byte) 0x00
    }));
  }

  @Test
  public void testBytesToHexAllSymbols() {
    assertEquals("0123456789ABCDEF", TorrentUtils.byteArrayToHexString(new byte[]{
            (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
            (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF
    }));
  }
}
