"""Matching core transactions against a partner statement.

The rule is deliberately conservative: anything that is not an exact match on
both reference and amount becomes a discrepancy for a human to review. A
reconciliation job that quietly "fixes" a mismatch hides exactly the loss it
exists to surface.
"""

from __future__ import annotations

from collections.abc import Iterable

from .models import (
    CoreTransaction,
    Discrepancy,
    MatchStatus,
    PartnerRecord,
    ReconciliationReport,
)


def reconcile(
    core_transactions: Iterable[CoreTransaction],
    partner_records: Iterable[PartnerRecord],
) -> ReconciliationReport:
    """Compare both sides and report every difference.

    Matching is by reference first; amount is then compared. A reference that
    exists on only one side is reported as missing rather than dropped.
    """
    core_by_key: dict[str, CoreTransaction] = {}
    core_duplicates: list[CoreTransaction] = []
    core_count = 0
    for txn in core_transactions:
        core_count += 1
        if txn.partner_key in core_by_key:
            core_duplicates.append(txn)
        else:
            core_by_key[txn.partner_key] = txn

    partner_by_key: dict[str, PartnerRecord] = {}
    partner_duplicates: list[PartnerRecord] = []
    partner_count = 0
    for record in partner_records:
        partner_count += 1
        if record.partner_ref in partner_by_key:
            partner_duplicates.append(record)
        else:
            partner_by_key[record.partner_ref] = record

    report = ReconciliationReport(
        core_count=core_count,
        partner_count=partner_count,
    )

    for txn in core_duplicates:
        report.items.append(Discrepancy(
            status=MatchStatus.DUPLICATE_IN_CORE, key=txn.partner_key,
            core_amount=txn.amount, detail="duplicate ledger reference",
        ))
    for record in partner_duplicates:
        report.items.append(Discrepancy(
            status=MatchStatus.DUPLICATE_ON_PARTNER, key=record.partner_ref,
            partner_amount=record.amount, detail="duplicate partner reference",
        ))

    for key, txn in core_by_key.items():
        partner = partner_by_key.get(key)
        if partner is None:
            report.items.append(
                Discrepancy(
                    status=MatchStatus.MISSING_ON_PARTNER,
                    key=key,
                    core_amount=txn.amount,
                    detail=f"core settled {txn.amount} but partner reports nothing",
                )
            )
            continue

        if partner.amount != txn.amount:
            report.items.append(
                Discrepancy(
                    status=MatchStatus.AMOUNT_MISMATCH,
                    key=key,
                    core_amount=txn.amount,
                    partner_amount=partner.amount,
                    detail=(
                        f"core {txn.amount} vs partner {partner.amount} "
                        f"(delta {txn.amount - partner.amount})"
                    ),
                )
            )
            continue

        report.items.append(
            Discrepancy(
                status=MatchStatus.MATCHED,
                key=key,
                core_amount=txn.amount,
                partner_amount=partner.amount,
                detail="exact match",
            )
        )

    for key, record in partner_by_key.items():
        if key not in core_by_key:
            report.items.append(
                Discrepancy(
                    status=MatchStatus.MISSING_IN_CORE,
                    key=key,
                    partner_amount=record.amount,
                    detail=f"partner reports {record.amount} with no matching core transaction",
                )
            )

    return report


def summary_line(report: ReconciliationReport) -> str:
    """One-line operator-facing summary."""
    verdict = "BALANCED" if report.is_balanced else "DISCREPANCIES FOUND"
    return (
        f"[{verdict}] core={report.core_count} partner={report.partner_count} "
        f"matched={report.matched_count} "
        f"unmatched={report.discrepancy_count}"
    )
