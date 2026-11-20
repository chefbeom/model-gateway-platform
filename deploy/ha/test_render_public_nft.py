import unittest

from render_public_nft import render


class PublicFirewallRendererTest(unittest.TestCase):
    def setUp(self):
        self.data = {"success": True, "result": {"ipv4_cidrs": ["1.1.1.0/24"], "ipv6_cidrs": ["2606:4700::/32"]}}

    def test_rules_scope_both_hooks_and_address_families_to_https(self):
        result = render(self.data)
        self.assertIn("hook input", result)
        self.assertIn("hook forward", result)
        self.assertEqual(result.count('iifname != { "lo", "tailscale0" } tcp dport 443'), 2)
        self.assertEqual(result.count('ct status dnat ct original proto-dst 443 tcp dport 443'), 2)
        self.assertNotIn("dport 80 ", result)
        self.assertIn("1.1.1.0/24", result)
        self.assertIn("2606:4700::/32", result)

    def test_rejects_broad_ranges(self):
        with self.assertRaises(ValueError):
            render({"success": True, "result": {"ipv4_cidrs": ["0.0.0.0/0"], "ipv6_cidrs": ["::/0"]}})


if __name__ == "__main__":
    unittest.main()
