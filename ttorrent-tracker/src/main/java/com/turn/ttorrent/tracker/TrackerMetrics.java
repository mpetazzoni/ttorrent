/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class TrackerMetrics {

    private final MetricRegistry metricRegistry;
    private final Object trackerId;
    private final List<String> names = new ArrayList<String>();

    public TrackerMetrics(@Nonnull MetricRegistry metricRegistry, Object trackerId) {
        this.metricRegistry = metricRegistry;
        this.trackerId = trackerId;
    }

    @Nonnull
    private String newMetricName(@Nonnull String name) {
        String n = MetricRegistry.name(getClass().getName(), "Tracker-" + trackerId, name);
        synchronized (names) {
            names.add(n);
        }
        return n;
    }

    @Nonnull
    public Counter addCounter(@Nonnull String name) {
        return metricRegistry.counter(newMetricName(name));
    }

    @Nonnull
    public <T> Gauge<T> addGauge(@Nonnull String name, @Nonnull Gauge<T> gauge) {
        return metricRegistry.register(newMetricName(name), gauge);
    }

    @Nonnull
    public Meter addMeter(@Nonnull String name, @Nonnull String item, @Nonnull TimeUnit unit) {
        return metricRegistry.meter(newMetricName(name));
    }

    public void shutdown() {
        for (String n : names) {
            metricRegistry.remove(n);
        }
    }
}
