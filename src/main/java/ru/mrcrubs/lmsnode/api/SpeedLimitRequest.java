package ru.mrcrubs.lmsnode.api;

import jakarta.validation.constraints.PositiveOrZero;

/** Speed limit in bytes per second; null or 0 removes the limit. */
public record SpeedLimitRequest(@PositiveOrZero Long maxSpeedBytes) {
}
