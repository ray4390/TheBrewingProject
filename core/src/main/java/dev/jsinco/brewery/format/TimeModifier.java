package dev.jsinco.brewery.format;

import dev.jsinco.brewery.configuration.Config;

import java.util.function.DoubleSupplier;

public enum TimeModifier {

    NORMAL(() -> 1200d),
    COOKING(() -> Config.config().cauldrons().cookingMinuteTicks()),
    AGING(() -> (double) Config.config().barrels().agingYearTicks() / (365 * 24 * 60));

    private final DoubleSupplier ticksPerMinute;

    TimeModifier(DoubleSupplier ticksPerMinute) {
        this.ticksPerMinute = ticksPerMinute;
    }

    public double getTicksPerMinute() {
        return this.ticksPerMinute.getAsDouble();
    }
}
