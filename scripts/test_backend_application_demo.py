"""Start-up behavior for the disposable full-stack demo."""

import unittest
from unittest.mock import patch

from scripts import backend_application_demo as demo


class WaitForTest(unittest.TestCase):
    def test_retries_reset_connection_until_service_is_ready(self):
        with patch.object(demo, "call", side_effect=[ConnectionResetError(104, "reset"), (200, {})]) as call:
            with patch.object(demo.time, "sleep"):
                demo.wait_for("http://127.0.0.1:8000/health", timeout=5)
        self.assertEqual(call.call_count, 2)

    def test_rejects_untrusted_reference_in_audit_url(self):
        with self.assertRaises(ValueError):
            demo.transaction_path("../../external")
        self.assertEqual(demo.transaction_path("TXN-20261117-123"), "TXN-20261117-123")


if __name__ == "__main__":
    unittest.main()
