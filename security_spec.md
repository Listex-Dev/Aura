# Security Specification — Aura Control

## 1. Data Invariants
1. All documents live strictly under `/users/{userId}/items/{itemId}`, `/users/{userId}/routines/{routineId}`, or `/users/{userId}/history/{logId}`.
2. Every read (`get`, `list`) and write (`create`, `update`, `delete`) requires an authenticated user (`request.auth != null`) whose `request.auth.uid == userId` AND `resource.data.userId == request.auth.uid` (for existing docs) or `request.resource.data.userId == request.auth.uid` (for incoming docs).
3. All document IDs (`userId`, `itemId`, `routineId`, `logId`) must match `^[a-zA-Z0-9_\-]+$` and be `<= 128` characters.
4. `createdAt` and `userId` are immutable on updates (`incoming().createdAt == existing().createdAt && incoming().userId == existing().userId`).
5. All string fields enforce strict length limits and all list fields enforce `.size() <= 10`.

## 2. The "Dirty Dozen" Payloads
1. **Unauthenticated Read**: `unauthDb.collection("users/alice/items").get()` -> Rejected (`request.auth != null`).
2. **Cross-User Read**: Bob reading `/users/alice/items/item_1` -> Rejected (`request.auth.uid == userId`).
3. **Identity Spoofing on Create**: Alice creating `/users/alice/items/item_1` with `userId: "bob"` -> Rejected (`incoming().userId == request.auth.uid`).
4. **Shadow Field Injection on Create**: Creating `SmartItem` with `isAdmin: true` -> Rejected (`hasOnly` check).
5. **Shadow Field Injection on Update**: Updating `SmartItem` with `hacked: 1` -> Rejected (`affectedKeys().hasOnly(...)`).
6. **Immutable `createdAt` Mutation**: Updating `SmartItem.createdAt` -> Rejected (`incoming().createdAt == existing().createdAt`).
7. **Immutable `userId` Mutation**: Updating `SmartItem.userId` to another user -> Rejected (`incoming().userId == existing().userId`).
8. **Enum Violation**: Creating `SmartItem` with `category: "UNKNOWN"` -> Rejected (`data.category in ['NOTE', 'TASK', 'REMINDER']`).
9. **Oversized String ("Denial of Wallet")**: Creating `SmartItem` with `title` of 500 chars -> Rejected (`data.title.size() <= 120`).
10. **Unbounded Array**: Creating `AutomationRoutine` with 15 `actionSteps` -> Rejected (`data.actionSteps.size() <= 10`).
11. **Future Timestamp Spoofing**: Creating `CommandLog` with `createdAt` in the future -> Rejected (`data.createdAt <= request.time`).
12. **Poisoned Document ID**: Creating document with ID containing spaces or >128 chars -> Rejected (`isValidId`).
