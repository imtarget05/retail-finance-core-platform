"""Automated End-Of-Day (EOD) Reconciliation Scheduler & Runner.

In regulated banking and retail payment platforms, daily reconciliation (EOD)
must run autonomously on a scheduled trigger (e.g. 02:30 ICT after settlement cutoff).
It pulls the daily ledger movements from the Java Core service, parses the partner CSV
statement, executes exact balance matching, and dispatches alerts on discrepancies.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from .models import CoreTransaction, Discrepancy, MatchStatus, PartnerRecord, ReconciliationReport
from .parser import parse_csv_statement
from .reconciler import reconcile

logger = logging.getLogger("reconciliation.scheduler")


@dataclass
class EodRunResult:
    """Summary of the scheduled reconciliation run."""
    total_core_records: int
    total_partner_records: int
    matched_count: int
    discrepancy_count: int
    is_balanced: bool
    discrepancies: list[Discrepancy]


class EodReconciliationRunner:
    """Orchestrates scheduled batch reconciliation without human intervention."""

    def __init__(
        self,
        core_feed_fetcher: Callable[[], list[CoreTransaction]],
        partner_statement_provider: Callable[[], str | bytes],
        alert_dispatcher: Callable[[str, list[Discrepancy]], Any] | None = None,
    ) -> None:
        self.core_feed_fetcher = core_feed_fetcher
        self.partner_statement_provider = partner_statement_provider
        self.alert_dispatcher = alert_dispatcher

    def execute_eod_batch(self) -> EodRunResult:
        """Executes the daily automated reconciliation job."""
        logger.info("Starting scheduled EOD reconciliation job...")

        # 1. Fetch settled ledger movements from Java Core
        core_records = self.core_feed_fetcher()

        # 2. Fetch and parse partner settlement CSV
        csv_content = self.partner_statement_provider()
        if isinstance(csv_content, bytes):
            csv_content = csv_content.decode("utf-8")
        partner_records = parse_csv_statement(csv_content)

        # 3. Perform exact matching
        report: ReconciliationReport = reconcile(core_records, partner_records)

        # 4. Assess balance state directly from domain report
        is_balanced = report.is_balanced
        discrepancies = report.discrepancies

        if not is_balanced:
            logger.warning(
                "EOD Reconciliation found %d discrepancies! Triggering alert.",
                len(discrepancies),
            )
            if self.alert_dispatcher:
                self.alert_dispatcher(
                    f"⚠️ EOD Alert: {len(discrepancies)} reconciliation gaps detected",
                    discrepancies,
                )
        else:
            logger.info("EOD Reconciliation BALANCED. All transactions accounted for.")

        return EodRunResult(
            total_core_records=report.core_count,
            total_partner_records=report.partner_count,
            matched_count=report.matched_count,
            discrepancy_count=report.discrepancy_count,
            is_balanced=is_balanced,
            discrepancies=discrepancies,
        )
