/**
 * Copyright (C) 2011-2012 Turn, Inc.
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
package com.turn.ttorrent.tracker.client;

import com.turn.ttorrent.protocol.tracker.TrackerMessage;

import java.net.URI;
import java.util.EventListener;
import javax.annotation.Nonnull;

/**
 * EventListener interface for objects that want to receive tracker responses.
 *
 * @author mpetazzoni
 */
public interface AnnounceResponseListener extends EventListener {

    /**
     * Handle an announce response event.
     */
    public void handleAnnounceResponse(@Nonnull URI tracker, @Nonnull TrackerMessage.AnnounceEvent event, @Nonnull TrackerMessage.AnnounceResponseMessage response);

    public void handleAnnounceFailed(@Nonnull URI tracker, @Nonnull TrackerMessage.AnnounceEvent event, @Nonnull String reason);
}