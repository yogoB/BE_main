package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.catalog.CatalogReader.CandidatePlan;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 기본료를 스마트초이스 공식 시세와 대조한다(D-20 의 1차 교차검증).
 *
 * <p><b>판정 규칙은 BE 가 갖는다</b> — 스마트초이스는 값을 보고할 뿐이다(절대 원칙 2).
 * 결과는 <b>표시·신뢰용</b>이며 금액에도 추천 순위에도 넣지 않는다(D-03·D-20).
 * 대조 대상은 요금제 <b>기본료</b>다. {@code baseline}(구독 포함 정가 합계)이 아니다.
 */
@Component
public class PriceCrossCheck {

    public enum Status {
        /** 공식 시세와 기본료가 같다. */
        MATCH,
        /** 공식 시세와 기본료가 다르다. 어느 쪽이 맞는지는 운영자가 판단한다. */
        MISMATCH,
        /** 스냅샷에 없어 대조하지 못했다. "틀렸다"가 아니라 "확인 못 했다"이다. */
        UNVERIFIED,
        /**
         * 스마트초이스가 이 통신사를 아예 주지 않아 대조 대상이 아니다.
         * 실측 응답의 통신사는 SKT·KT·LGU+ 뿐이라 알뜰폰 요금제(카탈로그 1,451건)가 여기 해당한다.
         * UNVERIFIED 와 구분한다 — "나중에 확인될 수도 있다"는 기대를 주지 않기 위해서다.
         */
        NOT_APPLICABLE
    }

    /** 한 요금제의 대조 결과. 확인 못 했으면 officialPrice 는 null 이다(0원으로 적지 않는다). */
    public record Verdict(Status status, Long officialPrice, String source, String sourceUrl, String checkedAt) {
        public static Verdict unverified() {
            return new Verdict(Status.UNVERIFIED, null, null, null, null);
        }
    }

    private final SmartChoiceSnapshotReader snapshots;

    public PriceCrossCheck(SmartChoiceSnapshotReader snapshots) {
        this.snapshots = snapshots;
    }

    public Verdict check(CandidatePlan candidate) {
        var lookup = snapshots.lookup(candidate.carrier(), candidate.plan().name());
        if (lookup.found().isPresent()) {
            var found = lookup.found().get();
            return new Verdict(found.planPrice() == candidate.plan().basePrice() ? Status.MATCH : Status.MISMATCH,
                    found.planPrice(), found.source(), found.sourceUrl(), found.collectedAt().toString());
        }
        // 아직 안 모았으면 "확인 못 했다"다. 모았는데 이 통신사가 없으면 애초에 대조 대상이 아니다.
        return lookup.collected() && !lookup.carrierCovered()
                ? new Verdict(Status.NOT_APPLICABLE, null, null, null, null)
                : Verdict.unverified();
    }
}
