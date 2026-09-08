package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.Accuracy;
import java.util.List;

/** 특정 조합 총비용 응답. 추천과 같은 CostResult 를 하나만 담는다. */
public record CalculatorResponse(Accuracy accuracy, List<MissingInput> missingInputs, CostResult result) {
}
