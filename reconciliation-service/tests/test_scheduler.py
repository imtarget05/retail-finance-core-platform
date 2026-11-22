"""Tests for EOD Automated Reconciliation Runner (app/scheduler.py)."""

from __future__ import annotations

from unittest.mock import Mock

from app.models import CoreTransaction
from app.scheduler import EodReconciliationRunner


def _create_core_txn(ref: str, amount: int) -> CoreTransaction:
    return CoreTransaction(
        txn_ref=ref,
        account_no="ACC-01",
        outlet_code="OUT-01",
        type="PAYMENT",
        amount=amount,
        fee=0,
        signed_amount=-amount,
    )


def test_eod_runner_balanced_when_all_records_match():
    core_txns = [
        _create_core_txn("REF-001", 150000),
        _create_core_txn("REF-002", 250000),
    ]
    csv_statement = """partner_ref,amount,status
REF-001,150000,SETTLED
REF-002,250000,SETTLED
"""
    mock_alert = Mock()
    runner = EodReconciliationRunner(
        core_feed_fetcher=lambda: core_txns,
        partner_statement_provider=lambda: csv_statement,
        alert_dispatcher=mock_alert,
    )

    result = runner.execute_eod_batch()

    assert result.is_balanced is True
    assert result.total_core_records == 2
    assert result.total_partner_records == 2
    assert result.discrepancy_count == 0
    mock_alert.assert_not_called()


def test_eod_runner_detects_discrepancies_and_dispatches_alert():
    core_txns = [
        _create_core_txn("REF-001", 150000),
        _create_core_txn("REF-002", 200000),  # Amount mismatch!
        _create_core_txn("REF-003", 500000),  # Missing on partner!
    ]
    csv_statement = """partner_ref,amount,status
REF-001,150000,SETTLED
REF-002,220000,SETTLED
"""
    mock_alert = Mock()
    runner = EodReconciliationRunner(
        core_feed_fetcher=lambda: core_txns,
        partner_statement_provider=lambda: csv_statement,
        alert_dispatcher=mock_alert,
    )

    result = runner.execute_eod_batch()

    assert result.is_balanced is False
    assert result.total_core_records == 3
    assert result.total_partner_records == 2
    assert result.discrepancy_count == 2
    mock_alert.assert_called_once()
    assert "2 reconciliation gaps detected" in mock_alert.call_args[0][0]
