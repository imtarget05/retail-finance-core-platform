"""Tests for the reconciliation matching rules.

Pure functions, no HTTP and no database: these pin down the matching contract
that operators and auditors depend on.
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from app.models import CoreTransaction, MatchStatus, PartnerRecord
from app.reconciler import reconcile, summary_line


def core(ref: str, amount: int, **kw) -> CoreTransaction:
    defaults = {
        "account_no": "ACC000001",
        "outlet_code": "OUTLET-HCM-01",
        "type": "CASH_OUT",
        "fee": 0,
        "signed_amount": -amount,
        "settled_at": datetime(2026, 10, 2, 9, 0, tzinfo=timezone.utc),
    }
    return CoreTransaction(txn_ref=ref, amount=amount, **{**defaults, **kw})


def partner(ref: str, amount: int, **kw) -> PartnerRecord:
    return PartnerRecord(partner_ref=ref, amount=amount, **kw)


class TestCleanMatch:
    def test_identical_sides_are_balanced(self):
        report = reconcile(
            [core("TXN-1", 50_000), core("TXN-2", 25_000)],
            [partner("TXN-1", 50_000), partner("TXN-2", 25_000)],
        )

        assert report.is_balanced
        assert report.matched_count == 2
        assert report.core_count == 2
        assert report.partner_count == 2

    def test_empty_statement_against_empty_core_is_balanced(self):
        report = reconcile([], [])
        assert report.is_balanced
        assert report.core_count == 0

    def test_summary_reports_balanced(self):
        report = reconcile([core("TXN-1", 100)], [partner("TXN-1", 100)])
        assert "BALANCED" in summary_line(report)
        assert "unmatched=0" in summary_line(report)


class TestDiscrepancies:
    def test_settled_locally_but_absent_from_partner(self):
        report = reconcile([core("TXN-1", 50_000)], [])

        assert not report.is_balanced
        assert report.discrepancy_count == 1
        assert report.discrepancies[0].status is MatchStatus.MISSING_ON_PARTNER
        assert report.discrepancies[0].key == "TXN-1"
        assert report.discrepancies[0].delta == 50_000

    def test_partner_reports_something_we_never_settled(self):
        report = reconcile([], [partner("TXN-9", 7_000)])

        assert not report.is_balanced
        assert report.discrepancies[0].status is MatchStatus.MISSING_IN_CORE
        assert report.discrepancies[0].delta == -7_000

    def test_same_reference_different_amount_is_a_mismatch(self):
        report = reconcile(
            [core("TXN-1", 50_000)],
            [partner("TXN-1", 49_500)],
        )

        gap = report.discrepancies[0]
        assert gap.status is MatchStatus.AMOUNT_MISMATCH
        assert gap.delta == 500
        assert "core 50000 vs partner 49500" in gap.detail

    def test_mixed_report_separates_matched_from_unmatched(self):
        report = reconcile(
            [core("TXN-1", 50_000), core("TXN-2", 30_000)],
            [partner("TXN-1", 50_000), partner("TXN-3", 10_000)],
        )

        statuses = {d.key: d.status for d in report.items}
        assert statuses["TXN-1"] is MatchStatus.MATCHED
        assert statuses["TXN-2"] is MatchStatus.MISSING_ON_PARTNER
        assert statuses["TXN-3"] is MatchStatus.MISSING_IN_CORE
        assert report.matched_count == 1
        assert report.discrepancy_count == 2
        assert len(report.items) == 3


class TestDuplicateHandling:
    def test_duplicate_core_reference_does_not_hide_the_first_row(self):
        report = reconcile(
            [core("TXN-1", 10_000), core("TXN-1", 99_000)],
            [partner("TXN-1", 10_000)],
        )

        # The second row is collapsed, so the matched amount is the first one.
        matched = [d for d in report.items if d.status is MatchStatus.MATCHED]
        assert len(matched) == 1
        assert matched[0].core_amount == 10_000
        assert report.core_count == 1

    def test_duplicate_partner_reference_collapses_to_one(self):
        report = reconcile(
            [core("TXN-1", 10_000)],
            [partner("TXN-1", 10_000), partner("TXN-1", 10_000)],
        )

        assert report.partner_count == 1
        assert report.is_balanced


class TestReportSerialisation:
    def test_to_dict_is_json_ready(self):
        import json

        report = reconcile([core("TXN-1", 5_000)], [])
        payload = report.to_dict()

        json.dumps(payload)  # must not raise
        assert payload["is_balanced"] is False
        assert payload["by_status"]["MISSING_ON_PARTNER"] == 1
        assert payload["discrepancies"][0]["key"] == "TXN-1"

    def test_balanced_report_has_no_discrepancy_entries(self):
        report = reconcile([core("TXN-1", 5_000)], [partner("TXN-1", 5_000)])
        payload = report.to_dict()

        assert payload["is_balanced"] is True
        assert payload["discrepancy_count"] == 0
        assert payload["by_status"] == {"MATCHED": 1}