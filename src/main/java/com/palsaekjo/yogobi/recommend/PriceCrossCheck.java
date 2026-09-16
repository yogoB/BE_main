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
        UNVERIFIED
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
        return snapshots.find(candidate.carrier(), candidate.plan().name())
                .map(found -> new Verdict(
                        found.planPrice() == candidate.plan().basePrice() ? Status.MATCH : Status.MISMATCH,
                        found.planPrice(), found.source(), found.sourceUrl(), found.collectedAt().toString()))
                .orElseGet(Verdict::unverified);
    }
}
