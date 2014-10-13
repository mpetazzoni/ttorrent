/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker.servlet;

import com.google.common.collect.Multimap;
import com.google.common.net.InetAddresses;
import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPAnnounceRequestMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPTrackerMessage;
import com.turn.ttorrent.tracker.TrackerService;
import com.turn.ttorrent.tracker.TrackerUtils;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 *
 * @author shevek
 */
public class ServletTrackerService extends TrackerService {

    public void process(@Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response) throws IOException, TrackerMessage.MessageValidationException {
        Multimap<String, String> params = TrackerUtils.parseQuery(request.getParameterMap());
        HTTPAnnounceRequestMessage announceRequest = HTTPAnnounceRequestMessage.fromParams(params);
        InetAddress clientAddress = InetAddresses.forString(request.getRemoteAddr());
        HTTPTrackerMessage announceResponse = super.process(new InetSocketAddress(clientAddress, request.getRemotePort()), announceRequest);
    }
}
