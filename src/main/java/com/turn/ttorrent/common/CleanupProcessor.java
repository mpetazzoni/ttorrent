/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.ttorrent.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Sergey.Pak
 *         Date: 10/8/13
 *         Time: 4:47 PM
 */
public class CleanupProcessor implements  Runnable{

  private static final int TIMEOUT = 5000;

  private List<Cleanable> myCleanables = new CopyOnWriteArrayList<Cleanable>();
  private boolean stop = false;

  @Override
  public void run() {
    try {
      while(!stop){
        Thread.sleep(TIMEOUT);
        for (Cleanable cleanable : myCleanables) {
          cleanable.cleanUp();
        }
      }
    } catch (InterruptedException e) {

    }
  }

  public void registerCleanable(final Cleanable cleanable){
    myCleanables.add(cleanable);
  }

  public void unregisterCleanable(final Cleanable cleanable){
    myCleanables.remove(cleanable);
  }
}
