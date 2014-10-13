/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker.servlet;

import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 *
 * @author shevek
 */
public class TrackerServlet extends HttpServlet {

    private final ServletTrackerService service;

    public TrackerServlet(@Nonnull ServletTrackerService service) {
        this.service = service;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            service.process(request, response);
        } catch (TrackerMessage.MessageValidationException e) {
            throw new ServletException(e);
        }
    }
}
