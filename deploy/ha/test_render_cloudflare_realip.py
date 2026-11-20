import unittest

from render_cloudflare_realip import render


class CloudflareRealIpTest(unittest.TestCase):
    def test_valid_ranges(self):
        text = render({"success": True, "result": {"ipv4_cidrs": ["1.1.1.0/24"], "ipv6_cidrs": ["2606:4700::/32"]}})
        self.assertIn("set_real_ip_from 1.1.1.0/24;", text)
        self.assertIn("set_real_ip_from 2606:4700::/32;", text)

    def test_refuses_bad_or_missing_ranges(self):
        for v4, v6 in [([], ["2606:4700::/32"]), (["0.0.0.0/0"], ["2606:4700::/32"]), (["10.0.0.0/8"], ["2606:4700::/32"]), (["1.1.1.0/24; return 200"], ["2606:4700::/32"]), (["1.1.1.0/24"], ["::/0"]), (["1.1.1.0/24", "1.1.1.0/24"], ["2606:4700::/32"])]:
            with self.subTest(v4=v4, v6=v6), self.assertRaises(ValueError):
                render({"success": True, "result": {"ipv4_cidrs": v4, "ipv6_cidrs": v6}})

    def test_refuses_failed_api_response(self):
        with self.assertRaises(ValueError):
            render({"success": False, "result": {}})


if __name__ == "__main__":
    unittest.main()
