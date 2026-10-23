"""Read-only, deterministic agent pipeline for reconciliation exceptions.

Each agent has one bounded responsibility. The output is advice for an
operator, never a command to mutate the ledger or a partner statement.
"""

from __future__ import annotations

from .models import Discrepancy, MatchStatus, ReconciliationReport


class EvidenceAgent:
    name = "evidence"

    def run(self, item: Discrepancy) -> dict:
        return {
            "status": item.status.value,
            "core_amount": item.core_amount,
            "partner_amount": item.partner_amount,
            "delta": item.delta,
        }


class RiskAgent:
    name = "risk"

    def run(self, item: Discrepancy, evidence: dict) -> str:
        if item.status in {
            MatchStatus.DUPLICATE_IN_CORE,
            MatchStatus.DUPLICATE_ON_PARTNER,
            MatchStatus.MISSING_IN_CORE,
        }:
            return "P1"
        return "P1" if abs(evidence["delta"]) >= 100_000 else "P2"


class ActionAgent:
    name = "action"

    def run(self, item: Discrepancy) -> str:
        actions = {
            MatchStatus.DUPLICATE_IN_CORE: "Verify ledger uniqueness and investigate the source event before closing the run.",
            MatchStatus.DUPLICATE_ON_PARTNER: "Ask the partner to confirm duplicate rows and statement identity.",
            MatchStatus.MISSING_IN_CORE: "Verify the partner reference and search the ledger and audit trail.",
            MatchStatus.MISSING_ON_PARTNER: "Check partner settlement delay and the next statement window.",
            MatchStatus.AMOUNT_MISMATCH: "Compare gross amount, fee, and partner settlement terms.",
        }
        return actions[item.status]


def triage_report(report: ReconciliationReport) -> list[dict]:
    """Coordinate three bounded agents; preserve human control of all actions."""
    evidence_agent = EvidenceAgent()
    risk_agent = RiskAgent()
    action_agent = ActionAgent()
    results = []
    for item in report.discrepancies:
        evidence = evidence_agent.run(item)
        results.append({
            "reference": item.key,
            "evidence": evidence,
            "priority": risk_agent.run(item, evidence),
            "recommended_action": action_agent.run(item),
            "requires_human_approval": True,
            "agents": [evidence_agent.name, risk_agent.name, action_agent.name],
        })
    return results
