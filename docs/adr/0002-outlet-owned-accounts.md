# ADR 0002: Outlet ownership for POS account and transaction access

Status: proposed for review (2026-10-23)

## Business risk

A cashier at outlet A could previously read another outlet's account and
transaction, post to it with a supplied `outletCode`, or replay a known
idempotency key. Account opening used the first outlet in the database for the
opening movement, regardless of the cashier's assigned outlet. This could
misattribute cash and expose customer names and balances.

## Decision

The JWT proves identity; its role and outlet claims cannot grant privileges.
The current enabled `app_user` row is authoritative for role and outlet on
each protected request. Disabling or demoting a user immediately removes
their former API privileges, even while their JWT has not expired.
POS_STAFF and OUTLET_MANAGER may open accounts and post movements
only for their assigned outlet and accounts owned by that outlet. The request
`outletCode` must match that assignment. OPS and ADMIN may read accounts and
transactions across outlets; OPS retains the settled feed. Neither OPS nor
ADMIN can create accounts or post movements through these endpoints. A replay
checks access to both the original transaction outlet and its account before
comparing payloads or returning the original result. Denials use a generic
403 without customer details.

`account.outlet_id` is additive and nullable for an upgrade from V1. New
accounts are assigned at creation, including zero-balance accounts. Every V1
account remains unresolved (`NULL`) after migration and fails closed for
POS/manager access. OPS/ADMIN can inspect these records. An owner may be
assigned only through a separate approved process using independent source
records and an audit trail; this migration performs no automatic assignment.
The transaction ledger continues to hold the outlet of each movement.

## Trade-off and recovery

This policy treats the account's opening outlet as its owner. It blocks a
customer from transacting at another outlet until a reviewed transfer-of-owner
workflow exists. Historical user reassignments are not tracked in V1, and the
old opening movement may have been attributed to the arbitrary first outlet.
Even agreement between a cashier's current outlet and that movement is not
proof of historical ownership. Do not bulk-fill NULL or infer ownership from
the V1 transaction or current user records.

Apply V2 after a database backup and migration dry run on a restored copy.
The additive column and nullable legacy rows let old readers continue during
rollout, but old application writers must be stopped before relying on this
authorization rule. Rollback application code only with a reviewed security
waiver; dropping the column would erase verified ownership. Restore the
pre-migration backup if V2 itself fails before application rollout.

## Evidence gate

`OutletAuthorizationIT` covers cross-outlet reads, posting, replay and
zero-opening legacy denial on MySQL. `AccountOutletUpgradeIT` upgrades a V1
database with a reassigned cashier and checks that every old owner remains
unresolved. `StaleJwtAuthorizationIT` proves demoted/disabled OPS tokens cannot
read the ledger feed or audit history. `FlywaySchemaValidationIT` validates
the fresh migrated schema. No production authorization claim follows from
these synthetic tests alone.
