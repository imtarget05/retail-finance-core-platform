import asyncio
from datetime import datetime, timezone

import httpx
from app.main import fetch_settled


def test_fetch_settled_reads_every_page(monkeypatch):
    monkeypatch.setattr("app.main.CORE_BASE_URL", "http://core")
    seen_pages = []

    def respond(request):
        page = int(request.url.params["page"])
        seen_pages.append(page)
        rows = [{"txnRef": f"TXN-{page}-{i}", "type": "CASH_IN", "amount": 1}
                for i in range(2)] if page < 2 else []
        return httpx.Response(200, json=rows)

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            return await fetch_settled(client, datetime(2026, 10, 1, tzinfo=timezone.utc),
                                       datetime(2026, 10, 2, tzinfo=timezone.utc), limit=2)

    rows = asyncio.run(run())

    assert seen_pages == [0, 1, 2]
    assert len(rows) == 4
