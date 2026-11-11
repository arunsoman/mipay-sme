# Plugin: Audit Viewer

Lets org admins see the audit trail of their own org and its subtree. Today only the platform can.

## Benefits of a plugin over building it into the core
- **Read-only over the existing audit log:** nothing is duplicated.
- **The tree is the visibility rule:** the same closure check as everywhere else decides what an admin can see.
- **Granted selectively:** for example only to regulated partners that need it.
- **Audit writing stays untouched:** the core keeps the single writer.
