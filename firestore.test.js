const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or create smart items", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const pastDate = new Date(Date.now() - 2000);
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/items`).get());
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/items`).doc("item_1").set({
      title: "Test",
      content: "Body",
      category: "NOTE",
      isCompleted: false,
      scheduledTime: "",
      userId: ALICE_UID,
      createdAt: pastDate,
      updatedAt: pastDate,
    })
  );
});

test("Authenticated user: can create, read, update, and list own smart items", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection(`users/${ALICE_UID}/items`).doc("item_1");
  const pastDate = new Date(Date.now() - 2000);

  await assertSucceeds(
    docRef.set({
      title: "Купить молоко",
      content: "Овсяное молоко 1л",
      category: "TASK",
      isCompleted: false,
      scheduledTime: "18:00",
      userId: ALICE_UID,
      createdAt: pastDate,
      updatedAt: pastDate,
    })
  );

  await assertSucceeds(docRef.get());
  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/items`).where("userId", "==", ALICE_UID).get()
  );

  await assertSucceeds(
    docRef.update({
      isCompleted: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Authenticated user: cannot access another user's items, routines, or history", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const pastDate = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/routines`).doc("routine_1").set({
      title: "Утренний режим",
      triggerPhrase: "Доброе утро",
      iconName: "wb_sunny",
      actionSteps: ["TORCH:OFF", "VOLUME:60"],
      userId: ALICE_UID,
      createdAt: pastDate,
      updatedAt: pastDate,
    })
  );

  await assertFails(bobDb.collection(`users/${ALICE_UID}/routines`).doc("routine_1").get());
  await assertFails(
    bobDb.collection(`users/${ALICE_UID}/routines`).where("userId", "==", ALICE_UID).get()
  );
});

test("Validation: rejects shadow fields and invalid category enum", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const pastDate = new Date(Date.now() - 2000);

  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/items`).doc("bad_item").set({
      title: "Заметка",
      content: "Текст",
      category: "INVALID_TYPE",
      isCompleted: false,
      scheduledTime: "",
      userId: ALICE_UID,
      createdAt: pastDate,
      updatedAt: pastDate,
    })
  );

  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/items`).doc("shadow_item").set({
      title: "Заметка",
      content: "Текст",
      category: "NOTE",
      isCompleted: false,
      scheduledTime: "",
      userId: ALICE_UID,
      isAdmin: true,
      createdAt: pastDate,
      updatedAt: pastDate,
    })
  );
});
