import csv
from datetime import date
import io
import json
from pathlib import Path
import tempfile
import unittest
import catalog_csv as catalog


class CatalogCsvTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inputs = self.root / "input"
        self.inputs.mkdir()
        self.store = self.root / "store"
        for name, header in catalog.HEADERS.items():
            source = catalog.ROOT / "db" / "seed" / (name + ".csv")
            self.inputs.joinpath(name + ".csv").write_bytes(source.read_bytes() if source.exists() else (header + "\n").encode())
        self.mobile("55000")

    def mobile(self, price):
        self.inputs.joinpath("mobile_plan.csv").write_text(catalog.HEADERS["mobile_plan"] + "\n"
            + f"SKT,검증 요금제,5G,{price},100000,100,100,,,,https://www.tworld.co.kr,2026-09-01\n")

    def review(self):
        blobs, _ = catalog.read_catalog(self.inputs)
        return {"files": {k: catalog.digest(v) for k, v in blobs.items()}, "expected_revision": catalog.current(self.store),
                "approved_by": "검수자", "reason": "공식 원자료 숫자 확인",
                "sources": [{"url": "https://www.tworld.co.kr", "usage_basis": "테스트 승인 자료", "checked_at": date.today().isoformat()}]}

    def test_publish_update_delete_and_preserve_history(self):
        first = catalog.publish(self.inputs, self.store, self.review())
        self.mobile("51000")
        second = catalog.publish(self.inputs, self.store, self.review())
        self.assertNotEqual(first, second)
        self.assertIn("55000", (self.store / "revisions" / first / "mobile_plan.csv").read_text())
        self.inputs.joinpath("mobile_plan.csv").write_text(catalog.HEADERS["mobile_plan"] + "\n")
        with self.assertRaises(ValueError):
            catalog.publish(self.inputs, self.store, self.review())
        self.assertEqual(second, catalog.current(self.store))
        third = catalog.publish(self.inputs, self.store, self.review(), allow_deletions=True)
        self.assertEqual(third, catalog.current(self.store))

    def test_unapproved_changed_or_concurrent_review_never_moves_current(self):
        original = catalog.publish(self.inputs, self.store, self.review())
        review = self.review()
        review["approved_by"] = ""
        with self.assertRaises(ValueError): catalog.publish(self.inputs, self.store, review)
        review = self.review()
        self.mobile("50000")
        with self.assertRaises(ValueError): catalog.publish(self.inputs, self.store, review)
        review = self.review()
        review["expected_revision"] = ""
        with self.assertRaises(ValueError): catalog.publish(self.inputs, self.store, review)
        self.assertEqual(original, catalog.current(self.store))

    def test_invalid_money_source_and_foreign_key_rejected(self):
        for value in ("-1", "0", "2.5", str(2**63), "=1+1"):
            self.mobile(value)
            with self.assertRaises(ValueError): catalog.read_catalog(self.inputs)
        self.mobile("50000")
        path = self.inputs / "mobile_plan.csv"
        path.write_text(path.read_text().replace("https://www.tworld.co.kr", ""))
        with self.assertRaises(ValueError): catalog.read_catalog(self.inputs)
        self.mobile("50000")
        path = self.inputs / "subscription_tier.csv"
        path.write_text(path.read_text().replace("1,1,광고형", "1,999,광고형"))
        with self.assertRaises(ValueError): catalog.read_catalog(self.inputs)


if __name__ == "__main__":
    unittest.main()
