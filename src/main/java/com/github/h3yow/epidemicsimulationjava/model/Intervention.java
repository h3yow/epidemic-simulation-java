package com.github.h3yow.epidemicsimulationjava.model;

/**
 * An intervention active during the half-open window [startHour, endHour).
 *
 * @param type     intervention category
 * @param startHour first hour (inclusive, 0-based) at which the intervention is active
 * @param endHour   first hour (exclusive) at which the intervention is no longer active
 * @param effect    for SOCIAL_DISTANCING/MASKING: transmission multiplier in (0, 1];
 *                  for TREATMENT: additive increment to gamma in (0, 1]
 */
public record Intervention(InterventionType type, int startHour, int endHour, double effect) {

    /** @return true if the intervention is active during the given hour. */
    public boolean activeAt(int hour) {
        return hour >= startHour && hour < endHour;
    }
}
