/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker.spring;

import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import com.turn.ttorrent.tracker.servlet.ServletTrackerService;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
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

    private final ServletTrackerService service;

    @Autowired
    public TrackerController(@Nonnull ServletTrackerService service) {
        this.service = service;
    }

    @RequestMapping(value = "/")
    public void request(
            @Nonnull HttpServletRequest request,
            @Nonnull HttpServletResponse response) throws ServletException, IOException, TrackerMessage.MessageValidationException {
        service.process(request, response);
    }
}
