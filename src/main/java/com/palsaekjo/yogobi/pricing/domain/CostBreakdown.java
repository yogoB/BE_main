package com.palsaekjo.yogobi.pricing.domain;

import com.palsaekjo.yogobi.common.Accuracy;
import java.util.List;

public record CostBreakdown(
        List<CostLine> lines,
        long baseline,
        long effectiveMonthlyCost,
        long monthlySavings,
        long annualSavings,
        Accuracy accuracy,
        List<String> missingInputs
) {
}
