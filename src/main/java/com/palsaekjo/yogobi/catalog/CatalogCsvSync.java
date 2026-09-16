package com.palsaekjo.yogobi.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 운영 도구가 발행한 불변 CSV 버전만 검증해 조회 DB에 반영한다. 외부 요청 없음. */
@Component
@Order(3)
public class CatalogCsvSync implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogCsvSync.class);
    static final List<String> DATASETS = List.of("subscription_service", "subscription_tier", "bundle_product", "mobile_plan", "plan_benefit");
    private final CatalogSeedLoader loader;
    private final ObjectMapper json;
    private final String directory;
    private String applied;

    public CatalogCsvSync(CatalogSeedLoader loader, ObjectMapper json, @Value("${CATALOG_CSV_DIR:}") String directory) {
        this.loader = loader;
        this.json = json;
        this.directory = directory;
    }

    @Override public void run(ApplicationArguments args) { scheduledSync(); }

    @Scheduled(fixedDelayString = "${yogobi.catalog.csv-sync-delay-ms:60000}")
    public void scheduledSync() {
        try { sync(); }
        catch (Exception e) { log.warn("CSV 반영 실패 — 기존 DB 유지, 다음 주기에 재시도 ({})", e.getClass().getSimpleName()); }
    }

    synchronized boolean sync() throws Exception {
        if (directory.isBlank()) return false;
        Path root = Path.of(directory);
        String revision = Files.readString(root.resolve("current")).strip();
        if (!revision.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("CSV revision 형식 오류");
        if (revision.equals(applied)) return false;
        Path version = root.resolve("revisions").resolve(revision);
        byte[] manifestBytes = Files.readAllBytes(version.resolve("manifest.json"));
        if (!hash(manifestBytes).equals(revision)) throw new IllegalArgumentException("CSV manifest 해시 불일치");
        var manifest = json.readTree(manifestBytes);
        if (manifest.path("approved_by").asText().isBlank()) throw new IllegalArgumentException("CSV 승인 기록 없음");
        var resources = new LinkedHashMap<String, Resource>();
        for (String dataset : DATASETS) {
            Path file = version.resolve(dataset + ".csv");
            if (Files.size(file) > 10_000_000) throw new IllegalArgumentException("CSV 크기 초과");
            byte[] bytes = Files.readAllBytes(file);
            if (!hash(bytes).equals(manifest.path("files").path(dataset).asText()))
                throw new IllegalArgumentException("CSV 파일 해시 불일치");
            resources.put(dataset, new ByteArrayResource(bytes));
        }
        if (!revision.equals(Files.readString(root.resolve("current")).strip())) return false;
        loader.loadSnapshot(resources); // 동일 바이트를 단일 DB 트랜잭션으로 반영한다.
        applied = revision;
        log.info("CSV 카탈로그 반영 완료: {}", revision);
        return true;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
