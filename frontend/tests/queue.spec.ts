import { test, expect, type BrowserContext, type Page } from "@playwright/test";

test("two students, two staff, refresh, live updates, offline recovery and privacy", async ({
  browser,
}) => {
  const password = process.env.DEMO_STAFF_PASSWORD;
  if (!password)
    throw new Error(
      "Set DEMO_STAFF_PASSWORD to the explicitly provisioned demo password",
    );
  const contexts: BrowserContext[] = [];
  const make = async (mobile = false) => {
    const c = await browser.newContext(
      mobile ? { viewport: { width: 390, height: 844 }, isMobile: true } : {},
    );
    contexts.push(c);
    return c.newPage();
  };
  const [alex, sam, one, two, board] = await Promise.all([
    make(),
    make(),
    make(true),
    make(true),
    make(),
  ]);
  const login = async (page: Page, name: string) => {
    await page.goto("/staff");
    await page.getByLabel("Username", { exact: true }).fill(name);
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in →", exact: true }).click();
    await expect(
      page.getByText(`Signed in as ${name}.`, { exact: false }),
    ).toBeVisible();
  };
  try {
    await login(alex, "alex");
    await login(sam, "sam");
    if (
      await alex
        .getByRole("button", { name: "Open queue", exact: true })
        .isVisible()
    )
      await alex
        .getByRole("button", { name: "Open queue", exact: true })
        .click();
    for (const [page, name] of [
      [one, "Ada Private"],
      [two, "Lin Private"],
    ] as const) {
      await page.goto("/");
      await page.getByLabel("What should we call you?").fill(name);
      await page.getByRole("button", { name: "Join the queue" }).click();
      await expect(page.locator(".ticket-number")).toBeVisible();
    }
    const first = await one.locator(".ticket-number").innerText(),
      second = await two.locator(".ticket-number").innerText();
    expect(first).not.toBe(second);
    await one.reload();
    await expect(one.locator(".ticket-number")).toHaveText(first);
    await one.context().setOffline(true);
    await Promise.all([
      alex.getByRole("button", { name: "Call next student" }).click(),
      sam.getByRole("button", { name: "Call next student" }).click(),
    ]);
    const a = alex.locator(".assignment-number"),
      b = sam.locator(".assignment-number");
    await expect(a).toBeVisible();
    await expect(b).toBeVisible();
    expect(await a.innerText()).not.toBe(await b.innerText());
    await one.context().setOffline(false);
    await expect(one.getByText("You’re up", { exact: true })).toBeVisible({
      timeout: 20000,
    });
    await expect(two.getByText("You’re up", { exact: true })).toBeVisible({
      timeout: 20000,
    });
    await board.goto("/display");
    await expect(board.getByText(first, { exact: true })).toBeVisible();
    await expect(board.getByText("Ada Private")).toHaveCount(0);
    await expect(board.getByText("Lin Private")).toHaveCount(0);
    const publicData = await (await board.request.get("/api/public")).text();
    expect(publicData).not.toContain("displayName");
    expect(publicData).not.toContain("Ada Private");
    await alex
      .getByRole("button", { name: "Start service", exact: true })
      .click();
    await alex.getByRole("button", { name: "Complete service" }).click();
    await sam.getByRole("button", { name: "Mark no-show" }).click();
    await expect(a).toHaveCount(0);
    await expect(b).toHaveCount(0);
    await alex
      .getByRole("button", { name: "Close queue", exact: true })
      .click();
    const third = await make(true);
    await third.goto("/");
    await expect(
      third.getByRole("button", { name: "The queue is currently closed" }),
    ).toBeDisabled();
    await alex.getByRole("button", { name: "Open queue", exact: true }).click();
    await third.getByRole("button", { name: "Join the queue" }).click();
    await third.getByRole("button", { name: "Leave queue" }).click();
    await expect(third.getByText(/Cancelled/)).toBeVisible();
    await alex.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(
      alex.getByRole("button", { name: "Sign in →", exact: true }),
    ).toBeVisible();
  } finally {
    for (const c of contexts) await c.close();
  }
});

test("missed SSE events reconcile through polling", async ({ browser }) => {
  const password = process.env.DEMO_STAFF_PASSWORD;
  if (!password) throw new Error("Set DEMO_STAFF_PASSWORD");
  const staffContext = await browser.newContext(),
    studentContext = await browser.newContext();
  try {
    const staff = await staffContext.newPage(),
      student = await studentContext.newPage();
    await staff.goto("/staff");
    await staff.getByLabel("Username", { exact: true }).fill("alex");
    await staff.getByLabel("Password", { exact: true }).fill(password);
    await staff.getByRole("button", { name: "Sign in →", exact: true }).click();
    await expect(
      staff.getByRole("button", { name: "Call next student" }),
    ).toBeVisible();
    if (
      await staff
        .getByRole("button", { name: "Open queue", exact: true })
        .isVisible()
    )
      await staff
        .getByRole("button", { name: "Open queue", exact: true })
        .click();
    await student.route("**/api/events", (route) => route.abort());
    await student.goto("/");
    await student.getByRole("button", { name: "Join the queue" }).click();
    await expect(student.locator(".ticket-number")).toBeVisible();
    await staff.getByRole("button", { name: "Call next student" }).click();
    await expect(student.getByText("You’re up", { exact: true })).toBeVisible({
      timeout: 20000,
    });
    await staff.getByRole("button", { name: "Mark no-show" }).click();
  } finally {
    await staffContext.close();
    await studentContext.close();
  }
});
