package com.polysign.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Common detector configuration shared across all price detectors.
 *
 * Binds {@code polysign.detectors.common.*} from application.yml.
 *
 * Defaults preserve pre-existing behaviour (extreme zone cutoffs at 5¢ and 95¢)
 * so no production behaviour changes unless the values are overridden in config.
 */
@Component
@ConfigurationProperties(prefix = "polysign.detectors.common")
public class CommonDetectorProperties {

    /** Markets below this price are in the extreme low tail (default 0.05). */
    private double extremeZoneLow  = 0.05;

    /** Markets above this price are in the extreme high tail (default 0.95). */
    private double extremeZoneHigh = 0.95;

    public double getExtremeZoneLow()          { return extremeZoneLow; }
    public void   setExtremeZoneLow(double v)  { this.extremeZoneLow = v; }
    public double getExtremeZoneHigh()         { return extremeZoneHigh; }
    public void   setExtremeZoneHigh(double v) { this.extremeZoneHigh = v; }
}
