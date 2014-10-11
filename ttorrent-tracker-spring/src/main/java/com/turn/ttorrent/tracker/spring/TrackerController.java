/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker.spring;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.net.InetAddresses;
import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPAnnounceRequestMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPTrackerMessage;
import com.turn.ttorrent.tracker.TrackerService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 *
 * @author shevek
 */
@RequestMapping("/announce")
public class TrackerController {

    private final TrackerService service;

    @Autowired
    public TrackerController(@Nonnull TrackerService service) {
        this.service = service;
    }

    @RequestMapping(value = "/")
    public void request(
            @Nonnull HttpServletRequest request,
            @Nonnull HttpServletResponse response) throws IOException, TrackerMessage.MessageValidationException {
        Multimap<String, String> params = HashMultimap.create();
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            for (String value : e.getValue())
                params.put(e.getKey(), value);
        }
        HTTPAnnounceRequestMessage announceRequest = HTTPAnnounceRequestMessage.fromParams(params);
        InetAddress clientAddress = InetAddresses.forString(request.getRemoteAddr());
        HTTPTrackerMessage announceResponse = service.process(new InetSocketAddress(clientAddress, request.getRemotePort()), announceRequest);
    }
}
