package com.palsaekjo.yogobi.catalog;

import com.palsaekjo.yogobi.catalog.CatalogReader.BenefitView;
import com.palsaekjo.yogobi.catalog.CatalogReader.PlanView;
import com.palsaekjo.yogobi.catalog.CatalogReader.ServiceView;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 카탈로그 마스터 읽기 전용 API (docs/architecture.md §3 MVP). */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final CatalogReader reader;

    public CatalogController(CatalogReader reader) {
        this.reader = reader;
    }

    @GetMapping("/services")
    public ApiResponse<List<ServiceView>> services() {
        return ApiResponse.ok(reader.listServices());
    }

    @GetMapping("/plans")
    public ApiResponse<List<PlanView>> plans() {
        return ApiResponse.ok(reader.listPlans());
    }

    @GetMapping("/plans/{id}/benefits")
    public ApiResponse<List<BenefitView>> benefits(@PathVariable long id) {
        return ApiResponse.ok(reader.listBenefits(id)
                .orElseThrow(() -> ApiException.planNotFound("요금제를 찾을 수 없습니다: " + id)));
    }
}
