from app.models import CoreTransaction, MatchStatus, PartnerRecord
from app.reconciler import reconcile
from app.triage import triage_report


def core(ref, amount):
    return CoreTransaction(ref, "ACC1", "OUT1", "CASH_OUT", amount, 0, -amount)


def test_three_agents_explain_an_unmatched_partner_row():
    report = reconcile([], [PartnerRecord("TXN-1", 500_000)])

    result = triage_report(report)

    assert len(result) == 1
    assert result[0]["reference"] == "TXN-1"
    assert result[0]["evidence"]["status"] == MatchStatus.MISSING_IN_CORE.value
    assert result[0]["priority"] == "P1"
    assert result[0]["recommended_action"]
    assert result[0]["requires_human_approval"] is True
    assert result[0]["agents"] == ["evidence", "risk", "action"]


def test_duplicate_is_high_priority_and_never_auto_corrected():
    report = reconcile([core("TXN-1", 100), core("TXN-1", 100)],
                       [PartnerRecord("TXN-1", 100)])

    result = triage_report(report)

    assert len(result) == 1
    assert result[0]["priority"] == "P1"
    assert result[0]["requires_human_approval"] is True


def test_balanced_report_produces_no_triage_items():
    report = reconcile([core("TXN-1", 100)], [PartnerRecord("TXN-1", 100)])
    assert triage_report(report) == []
