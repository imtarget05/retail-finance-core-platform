"""Reconciliation domain model.

Money is ``int`` (VND has no minor unit), matching the Java core exactly so a
value crosses the service boundary without any rounding decision.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum


class MatchStatus(str, Enum):
    """Outcome of comparing one internal transaction against partner records."""

    MATCHED = "MATCHED"
    """Present on both sides with the same reference and amount."""

    AMOUNT_MISMATCH = "AMOUNT_MISMATCH"
    """Same reference, different amount - usually a partner-side fee or FX slip."""

    MISSING_ON_PARTNER = "MISSING_ON_PARTNER"
    """We settled it but the partner never reports it."""

    MISSING_IN_CORE = "MISSING_IN_CORE"
    """The partner reports it but we have no such transaction."""

    DUPLICATE_IN_CORE = "DUPLICATE_IN_CORE"
    """The ledger feed contains the same transaction reference more than once."""

    DUPLICATE_ON_PARTNER = "DUPLICATE_ON_PARTNER"
    """The partner statement contains the same reference more than once."""


@dataclass(frozen=True)
class CoreTransaction:
    """A settled transaction pulled from the Java core service."""

    txn_ref: str
    account_no: str
    outlet_code: str
    type: str
    amount: int
    fee: int
    signed_amount: int
    settled_at: datetime | None = None

    @property
    def partner_key(self) -> str:
        """Key used to line up with the partner statement."""
        return self.txn_ref


@dataclass(frozen=True)
class PartnerRecord:
    """One line from a partner/bank statement file."""

    partner_ref: str
    amount: int
    posted_at: datetime | None = None
    outlet_code: str | None = None
    note: str | None = None


@dataclass
class Discrepancy:
    """A single item a human needs to look at."""

    status: MatchStatus
    key: str
    core_amount: int | None = None
    partner_amount: int | None = None
    detail: str = ""

    @property
    def delta(self) -> int:
        """Signed gap. Zero for a clean match."""
        core = self.core_amount or 0
        partner = self.partner_amount or 0
        return core - partner


@dataclass
class ReconciliationReport:
    """Result of matching one statement against the core ledger.

    ``items`` holds every reference that was compared, matched or not. Callers
    that only care about problems should filter on ``status``.
    """

    started_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    core_count: int = 0
    partner_count: int = 0
    items: list[Discrepancy] = field(default_factory=list)

    @property
    def is_balanced(self) -> bool:
        """True when every reference appears on both sides with equal amounts."""
        return all(d.status is MatchStatus.MATCHED for d in self.items)

    @property
    def matched_count(self) -> int:
        """How many references found an equal record on both sides."""
        return sum(1 for d in self.items if d.status is MatchStatus.MATCHED)

    @property
    def discrepancy_count(self) -> int:
        """How many references need a human decision."""
        return len(self.items) - self.matched_count

    @property
    def discrepancies(self) -> list[Discrepancy]:
        """Only the items that are not an exact match."""
        return [d for d in self.items if d.status is not MatchStatus.MATCHED]

    def to_dict(self) -> dict:
        by_status: dict[str, int] = {}
        for d in self.items:
            key = d.status.value
            by_status[key] = by_status.get(key, 0) + 1

        return {
            "started_at": self.started_at.isoformat(),
            "core_count": self.core_count,
            "partner_count": self.partner_count,
            "matched_count": self.matched_count,
            "discrepancy_count": self.discrepancy_count,
            "is_balanced": self.is_balanced,
            "by_status": by_status,
            "discrepancies": [
                {
                    "status": d.status.value,
                    "key": d.key,
                    "core_amount": d.core_amount,
                    "partner_amount": d.partner_amount,
                    "delta": d.delta,
                    "detail": d.detail,
                }
                for d in self.discrepancies
            ],
        }
