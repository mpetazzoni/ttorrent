/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker;

import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class TrackerMetrics {

    private final MetricsRegistry metricsRegistry;
    private final Object trackerId;
    private final List<MetricName> names = new ArrayList<MetricName>();

    public TrackerMetrics(@Nonnull MetricsRegistry metricsRegistry, Object trackerId) {
        this.metricsRegistry = metricsRegistry;
        this.trackerId = trackerId;
    }

    @Nonnull
    private MetricName newMetricName(String name) {
        MetricName n = new MetricName(getClass().getName(), "Tracker-" + trackerId, name);
        synchronized (names) {
            names.add(n);
        }
        return n;
    }

    @Nonnull
    public Counter addCounter(String name) {
        return metricsRegistry.newCounter(newMetricName(name));
    }

    @Nonnull
    public <T> Gauge<T> addGauge(String name, Gauge<T> gauge) {
        return metricsRegistry.newGauge(newMetricName(name), gauge);
    }

    @Nonnull
    public Meter addMeter(String name, String item, TimeUnit unit) {
        return metricsRegistry.newMeter(newMetricName(name), item, unit);
    }

    public void shutdown() {
        for (MetricName n : names) {
            metricsRegistry.removeMetric(n);
        }
    }
}
