from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)
BODY = {
    "statement_csv": "partner_ref,amount\nTXN-1,100\n",
    "window_start": "2026-10-01T00:00:00Z",
    "window_end": "2026-10-02T00:00:00Z",
}


def test_reconciliation_requires_configured_operator_token(monkeypatch):
    monkeypatch.setattr("app.main.RECONCILIATION_API_TOKEN", "test-secret")
    assert client.post("/api/v1/reconcile", json=BODY).status_code == 401
    assert client.post("/api/v1/reconcile", json=BODY,
                       headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_reconciliation_rejects_naive_time_window(monkeypatch):
    monkeypatch.setattr("app.main.RECONCILIATION_API_TOKEN", "test-secret")
    body = {**BODY, "window_start": "2026-10-01T00:00:00"}
    response = client.post("/api/v1/reconcile", json=body,
                           headers={"Authorization": "Bearer test-secret"})
    assert response.status_code == 422
