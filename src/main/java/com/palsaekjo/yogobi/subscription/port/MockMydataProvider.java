package com.palsaekjo.yogobi.subscription.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.palsaekjo.yogobi.common.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Mock 마이데이터(금융 표준 카드 승인내역 모방, data.md §6) 파서. 데모 기본 경로.
 * 승인(status=01)만, 취소(02)는 제외. 실제 연동 시 금융보안원 규격으로 필드 재확인(파서만 교체).
 */
@Component
public class MockMydataProvider implements PaymentHistoryProvider {
    private final ObjectMapper json;

    public MockMydataProvider(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String source() {
        return "MOCK_MYDATA";
    }

    @Override
    public List<ImportedPayment> parse(String content) {
        try {
            JsonNode list = json.readTree(content).path("approved_list");
            if (!list.isArray()) {
                throw ApiException.requiredMissing("approved_list", "카드 승인내역 파일을 읽지 못했어요. 카드사에서 내려받은 파일을 그대로 올려 주세요.");
            }
            var out = new ArrayList<ImportedPayment>();
            for (JsonNode n : list) {
                if (!"01".equals(n.path("status").asText())) {
                    continue; // 취소·기타 제외, 승인만
                }
                String merchant = n.path("merchant_name").isTextual() ? n.get("merchant_name").textValue().trim() : "";
                JsonNode amount = n.path("approved_amt");
                String dtime = n.path("approved_dtime").asText(""); // yyyyMMddHHmmss
                // NUL 은 DB text 가 거부해 가져오기 전체가 500 으로 롤백됐다(G-86 g).
                if (merchant.isBlank() || merchant.indexOf('\0') >= 0 || !amount.isIntegralNumber() || !amount.canConvertToLong()
                        || amount.longValue() < 0 || !"KRW".equals(n.path("currency_code").asText())
                        || !dtime.matches("[0-9]{14}")) {
                    throw ApiException.requiredMissing("approved_list", "읽을 수 없는 결제 항목이 있어요. 원화가 아닌 결제가 섞였다면 그 줄을 빼고 올려 주세요.");
                }
                out.add(new ImportedPayment(merchant, amount.longValue(), LocalDateTime.parse(dtime,
                        DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)).toLocalDate()));
            }
            return List.copyOf(out);
        } catch (ApiException e) {
            throw e;
        } catch (JsonProcessingException | RuntimeException e) {
            throw ApiException.requiredMissing("payload", "카드 승인내역 파일을 읽지 못했어요. 카드사에서 내려받은 파일을 그대로 올려 주세요.");
        }
    }
}
