"""Synthetic identities and request builders shared by the unit and integration tests."""

LOCAL_ROUTING = "883745000"
EXTERNAL_ROUTING = "808889588"
ALICE = {"user": "alice", "acct": "1011226111"}
BOB = {"user": "bob", "acct": "1033623433"}


def new_contact(**overrides):
    """A valid external contact request body (synthetic numbers)."""
    body = {"label": "Credit Union", "account_num": "9099791699",
            "routing_num": EXTERNAL_ROUTING, "is_external": True}
    body.update(overrides)
    return body
