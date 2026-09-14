package com.palsaekjo.yogobi.subscription;

import com.palsaekjo.yogobi.common.ApiResponse;
import com.palsaekjo.yogobi.subscription.port.MockMydataProvider;
import java.security.Principal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원 본인의 결제내역 업로드(마이데이터 본인전송). 인증(ROLE_MEMBER)+CSRF. 현재는 Mock 마이데이터 형식(data.md §6, A). */
@RestController
@RequestMapping("/api/v1/me")
public class MePaymentController {
    private final PaymentImportService imports;
    private final MockMydataProvider mockProvider;

    public MePaymentController(PaymentImportService imports, MockMydataProvider mockProvider) {
        this.imports = imports;
        this.mockProvider = mockProvider;
    }

    @PostMapping("/payments/import")
    public ApiResponse<PaymentImportService.Result> importPayments(@RequestBody String body, Principal principal) {
        return ApiResponse.ok(imports.importPayments(Long.parseLong(principal.getName()), mockProvider, body));
    }
}
